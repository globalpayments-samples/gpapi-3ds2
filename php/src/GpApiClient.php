<?php
declare(strict_types=1);

use GlobalPayments\Api\Entities\Address;
use GlobalPayments\Api\Entities\BrowserData;
use GlobalPayments\Api\Entities\Enums\AddressType;
use GlobalPayments\Api\Entities\Enums\AuthenticationSource;
use GlobalPayments\Api\Entities\Enums\Channel;
use GlobalPayments\Api\Entities\Enums\ChallengeWindowSize;
use GlobalPayments\Api\Entities\Enums\ColorDepth;
use GlobalPayments\Api\Entities\Enums\Environment;
use GlobalPayments\Api\Entities\Enums\IntervalToExpire;
use GlobalPayments\Api\Entities\Enums\MethodUrlCompletion;
use GlobalPayments\Api\Entities\GpApi\AccessTokenInfo;
use GlobalPayments\Api\Entities\ThreeDSecure;
use GlobalPayments\Api\PaymentMethods\CreditCardData;
use GlobalPayments\Api\ServiceConfigs\Gateways\GpApiConfig;
use GlobalPayments\Api\Services\GpApiService;
use GlobalPayments\Api\Services\Secure3dService;
use GlobalPayments\Api\ServicesContainer;

/**
 * Thin sample adapter around the official Global Payments PHP SDK.
 */
class GpApiClient
{
    private const CONFIG_NAME = 'gpapi-3ds-sample';
    private const GP_VERSION = '2021-03-22';
    private static bool $configured = false;

    public static function tokenizationConfig(): array
    {
        $config = self::sdkConfig(true);
        $token = GpApiService::generateTransactionKey($config);

        return [
            'env' => getenv('GP_API_ENVIRONMENT') ?: 'sandbox',
            'accessToken' => $token->accessToken,
            'accountName' => getenv('GP_TOKENIZATION_ACCOUNT_NAME') ?: ($token->tokenizationAccountName ?: (getenv('GP_ACCOUNT_NAME') ?: 'transaction_processing')),
            'merchantId' => $token->merchantId,
            'apiVersion' => self::GP_VERSION,
        ];
    }

    public static function checkEnrollment(array $input): ThreeDSecure
    {
        self::ensureConfigured();
        return Secure3dService::checkEnrollment(self::paymentMethod($input))
            ->withAmount($input['amount'] ?? $input['order']['amount'] ?? '10.00')
            ->withCurrency($input['currency'] ?? $input['order']['currency'] ?? 'GBP')
            ->execute(self::CONFIG_NAME);
    }

    public static function initiateAuthentication(array $input): ThreeDSecure
    {
        self::ensureConfigured();
        $seed = new ThreeDSecure();
        $seed->serverTransactionId = trim((string)($input['server_trans_id'] ?? ''));

        return Secure3dService::initiateAuthentication(self::paymentMethod($input), $seed)
            ->withAmount($input['amount'] ?? $input['order']['amount'] ?? '10.00')
            ->withCurrency($input['currency'] ?? $input['order']['currency'] ?? 'GBP')
            ->withOrderCreateDate(date('Y-m-d H:i:s'))
            ->withAddress(self::shippingAddress(), AddressType::SHIPPING)
            ->withAuthenticationSource(AuthenticationSource::BROWSER)
            ->withBrowserData(self::browserData($input['browser_data'] ?? []))
            ->withMethodUrlCompletion(self::methodCompletion($input['method_url_completion'] ?? null))
            ->execute(self::CONFIG_NAME);
    }

    public static function getAuthenticationData(string $serverTransactionId, string $amount = '10.00'): ThreeDSecure
    {
        self::ensureConfigured();
        return Secure3dService::getAuthenticationData()
            ->withServerTransactionId($serverTransactionId)
            ->withAmount($amount)
            ->execute(self::CONFIG_NAME);
    }

    public static function charge(array $input, ?ThreeDSecure $secure): object
    {
        self::ensureConfigured();
        $card = self::paymentMethod($input);
        if ($secure) {
            $card->threeDSecure = $secure;
        }

        return $card->charge($input['amount'] ?? '10.00')
            ->withCurrency($input['currency'] ?? 'GBP')
            ->execute(self::CONFIG_NAME);
    }

    public static function mapEnrollment(ThreeDSecure $secure): array
    {
        return [
            'server_trans_id' => $secure->serverTransactionId,
            'server_trans_ref' => $secure->providerServerTransRef ?? $secure->serverTransactionId,
            'enrolled' => $secure->enrolled,
            'message_version' => $secure->messageVersion ?? $secure->getVersion(),
            'method_url' => $secure->issuerAcsUrl ?? null,
            'method_data' => $secure->payerAuthenticationRequest ?? null,
        ];
    }

    public static function mapAuthentication(ThreeDSecure $secure): array
    {
        $status = $secure->status ?? $secure->enrolled ?? null;
        $data = [
            'server_trans_id' => $secure->serverTransactionId,
            'status' => $status,
            'acs_reference_number' => $secure->acsReferenceNumber ?? null,
            'acs_trans_id' => $secure->acsTransactionId ?? null,
            'acs_challenge_url' => $secure->issuerAcsUrl ?? null,
            'eci' => $secure->eci ?? null,
            'authentication_value' => $secure->authenticationValue ?? null,
            'ds_trans_ref' => $secure->directoryServerTransactionId ?? null,
            'message_version' => $secure->messageVersion ?? null,
            'server_trans_ref' => $secure->providerServerTransRef ?? $secure->serverTransactionId,
        ];

        if ($status === 'CHALLENGE_REQUIRED') {
            $data['challenge'] = [
                'requestUrl' => $secure->issuerAcsUrl,
                'encodedChallengeRequest' => $secure->payerAuthenticationRequest,
                'messageType' => $secure->messageType ?: 'creq',
            ];
        }

        return $data;
    }

    public static function mapTransaction(object $transaction): array
    {
        return [
            'transaction_id' => $transaction->transactionId ?? null,
            'status' => $transaction->responseMessage ?? null,
            'result_code' => $transaction->responseCode ?? null,
            'amount' => $transaction->balanceAmount ?? null,
            'currency' => $transaction->currency ?? null,
        ];
    }

    public static function sdkConfig(bool $tokenization = false): GpApiConfig
    {
        $config = new GpApiConfig();
        $config->appId = self::required('GP_APP_ID');
        $config->appKey = self::required('GP_APP_KEY');
        $config->environment = self::environment();
        $config->country = getenv('GP_COUNTRY') ?: 'GB';
        $config->channel = Channel::CardNotPresent;
        $config->merchantId = getenv('GP_PARTNER_MERCHANT_ID') ?: null;
        $config->methodNotificationUrl = getenv('METHOD_NOTIFICATION_URL') ?: null;
        $config->challengeNotificationUrl = getenv('CHALLENGE_NOTIFICATION_URL') ?: null;
        $config->merchantContactUrl = getenv('GP_MERCHANT_CONTACT_URL') ?: 'https://developer.globalpay.com/';
        if (!$tokenization) {
            $config->accessTokenInfo = self::accessTokenInfo();
        }

        if ($tokenization) {
            $config->permissions = ['PMT_POST_Create_Single'];
            $config->intervalToExpire = IntervalToExpire::TEN_MINUTES;
        }

        return $config;
    }

    public static function ensureConfigured(): void
    {
        if (self::$configured) {
            return;
        }
        ServicesContainer::configureService(self::sdkConfig(), self::CONFIG_NAME);
        self::$configured = true;
    }

    public static function jsonResponse(array $data, int $status = 200): void
    {
        http_response_code($status);
        header('Content-Type: application/json');
        header('Access-Control-Allow-Origin: *');
        echo json_encode($data);
        exit;
    }

    public static function errorResponse(\Throwable $e, int $status = 500): void
    {
        self::jsonResponse([
            'success' => false,
            'error' => $e->getMessage(),
            'raw' => [
                'type' => get_class($e),
                'code' => $e->getCode(),
            ],
        ], $status);
    }

    private static function paymentMethod(array $input): CreditCardData
    {
        $card = new CreditCardData();
        if (!empty($input['payment_method_id'])) {
            $card->token = (string)$input['payment_method_id'];
        } else {
            $card->number = (string)($input['card_number'] ?? '');
            $card->expMonth = (string)($input['exp_month'] ?? '');
            $card->expYear = (string)($input['exp_year'] ?? '');
            $card->cvn = (string)($input['cvn'] ?? '');
        }
        $card->cardHolderName = (string)($input['cardholder_name'] ?? 'Test User');
        return $card;
    }

    private static function browserData(array $input): BrowserData
    {
        $data = new BrowserData();
        $data->acceptHeader = (string)($input['accept_header'] ?? 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8');
        $data->colorDepth = self::colorDepth($input['color_depth'] ?? 24);
        $data->ipAddress = (string)($input['ip'] ?? '127.0.0.1');
        $data->javaEnabled = self::boolValue($input['java_enabled'] ?? false);
        $data->javaScriptEnabled = self::boolValue($input['javascript_enabled'] ?? true);
        $data->language = (string)($input['language'] ?? 'en-GB');
        $data->screenHeight = (string)($input['screen_height'] ?? 1080);
        $data->screenWidth = (string)($input['screen_width'] ?? 1920);
        $data->challengWindowSize = self::challengeWindow($input['challenge_window_size'] ?? null);
        $data->timeZone = (string)($input['timezone'] ?? '0');
        $data->userAgent = (string)($input['user_agent'] ?? 'Mozilla/5.0');
        return $data;
    }

    private static function shippingAddress(): Address
    {
        $address = new Address();
        $address->streetAddress1 = '1 Test Street';
        $address->city = 'London';
        $address->postalCode = 'SW1A 1AA';
        $address->countryCode = '826';
        return $address;
    }

    private static function accessTokenInfo(): AccessTokenInfo
    {
        $info = new AccessTokenInfo();
        $info->merchantId = getenv('GP_PARTNER_MERCHANT_ID') ?: null;
        $info->transactionProcessingAccountName = getenv('GP_ACCOUNT_NAME') ?: 'transaction_processing';
        $info->transactionProcessingAccountID = getenv('GP_ACCOUNT_ID') ?: null;
        $info->tokenizationAccountName = getenv('GP_TOKENIZATION_ACCOUNT_NAME') ?: null;
        return $info;
    }

    private static function required(string $name): string
    {
        $value = getenv($name);
        if (!$value) {
            throw new \RuntimeException($name . ' must be set');
        }
        return $value;
    }

    private static function environment(): string
    {
        return strtolower(getenv('GP_API_ENVIRONMENT') ?: 'sandbox') === 'production'
            ? Environment::PRODUCTION
            : Environment::TEST;
    }

    private static function boolValue(mixed $value): bool
    {
        if (is_bool($value)) {
            return $value;
        }
        return filter_var($value, FILTER_VALIDATE_BOOLEAN);
    }

    private static function colorDepth(mixed $value): string
    {
        $value = (int)$value;
        return match (true) {
            $value >= 48 => ColorDepth::FORTY_EIGHT_BITS,
            $value >= 32 => ColorDepth::THIRTY_TWO_BITS,
            $value >= 24 => ColorDepth::TWENTY_FOUR_BITS,
            $value >= 16 => ColorDepth::SIXTEEN_BITS,
            $value >= 15 => ColorDepth::FIFTEEN_BITS,
            $value >= 8 => ColorDepth::EIGHT_BITS,
            $value >= 4 => ColorDepth::FOUR_BITS,
            $value >= 2 => ColorDepth::TWO_BITS,
            default => ColorDepth::ONE_BIT,
        };
    }

    private static function challengeWindow(?string $value): string
    {
        $allowed = [
            ChallengeWindowSize::WINDOWED_250X400,
            ChallengeWindowSize::WINDOWED_390X400,
            ChallengeWindowSize::WINDOWED_500X600,
            ChallengeWindowSize::WINDOWED_600X400,
            ChallengeWindowSize::FULL_SCREEN,
        ];
        return in_array($value, $allowed, true) ? $value : ChallengeWindowSize::FULL_SCREEN;
    }

    private static function methodCompletion(?string $value): string
    {
        $allowed = [MethodUrlCompletion::YES, MethodUrlCompletion::NO, MethodUrlCompletion::UNAVAILABLE];
        return in_array($value, $allowed, true) ? $value : MethodUrlCompletion::UNAVAILABLE;
    }
}
