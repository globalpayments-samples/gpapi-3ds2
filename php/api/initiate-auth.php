<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createUnsafeMutable(__DIR__ . '/..');
$dotenv->load();

$input = json_decode(file_get_contents('php://input'), true) ?? [];

$serverTransIdRaw    = $input['server_trans_id']        ?? '';
$serverTransId       = preg_replace('/^AUT_/', '', $serverTransIdRaw);
$messageVersion      = $input['message_version']         ?? '2.1.0';
$methodUrlCompletion = $input['method_url_completion']   ?? 'UNAVAILABLE';
$cardNumber          = $input['card_number']             ?? '';
$expMonth            = $input['exp_month']               ?? '';
$expYear             = $input['exp_year']                ?? '';
$cardholderName      = $input['cardholder_name']         ?? 'Test User';
$browserData         = $input['browser_data']            ?? [];
$order               = $input['order']                   ?? [];
$amount              = $order['amount']                  ?? '10.00';
$currency            = $order['currency']                ?? 'GBP';

try {
    $raw = GpApiClient::request('POST', '/authentications', [
        'account_name' => getenv('GP_ACCOUNT_NAME') ?: 'transaction_processing',
        'account_id'   => getenv('GP_ACCOUNT_ID') ?: null,
        'merchant_id'  => getenv('GP_MERCHANT_ID') ?: null,
        'channel'      => 'CNP',
        'country'      => 'GB',
        'amount'       => GpApiClient::toMinorUnits($amount),
        'currency'     => $currency,
        'reference'    => GpApiClient::uuid(),
        'payment_method' => [
            'entry_mode' => 'ECOM',
            'card' => [
                'number'       => $cardNumber,
                'expiry_month' => $expMonth,
                'expiry_year'  => GpApiClient::twoDigitYear($expYear),
                'full_name'    => $cardholderName,
            ],
        ],
        'three_ds' => [
            'source'               => 'BROWSER',
            'preference'           => 'NO_PREFERENCE',
            'message_version'      => $messageVersion,
            'server_trans_ref'     => $serverTransId,
            'method_url_completion' => $methodUrlCompletion,
        ],
        'order' => [
            'amount'            => GpApiClient::toMinorUnits($amount),
            'currency'          => $currency,
            'reference'         => GpApiClient::uuid(),
            'address_indicator' => false,
            'date_time_created' => date('Y-m-d\TH:i:s.000\Z'),
        ],
        'payer' => [
            'email' => 'test@example.com',
            'billing_address' => [
                'line1'       => '1 Test Street',
                'city'        => 'London',
                'postal_code' => 'SW1A 1AA',
                'country'     => '826',
            ],
        ],
        'browser_data' => [
            'accept_header'        => $browserData['accept_header']        ?? 'text/html,application/xhtml+xml',
            'color_depth'          => (string) ($browserData['color_depth']       ?? '24'),
            'ip'                   => $browserData['ip']                   ?? '123.123.123.123',
            'java_enabled'         => (string) ($browserData['java_enabled']      ?? 'false'),
            'javascript_enabled'   => (string) ($browserData['javascript_enabled'] ?? 'true'),
            'language'             => $browserData['language']             ?? 'en-GB',
            'screen_height'        => (string) ($browserData['screen_height']     ?? '1080'),
            'screen_width'         => (string) ($browserData['screen_width']      ?? '1920'),
            'challenge_window_size' => $browserData['challenge_window_size'] ?? 'FULL_SCREEN',
            'timezone'             => (string) ($browserData['timezone']          ?? '0'),
            'user_agent'           => $browserData['user_agent']           ?? 'Mozilla/5.0',
        ],
        'notifications' => [
            'challenge_return_url'       => getenv('CHALLENGE_NOTIFICATION_URL'),
            'three_ds_method_return_url' => getenv('METHOD_NOTIFICATION_URL'),
        ],
    ]);

    GpApiClient::jsonResponse([
        'success' => true,
        'data' => [
            'server_trans_id'      => $raw['id'],
            'status'               => $raw['status'] ?? null,
            'acs_reference_number' => $raw['three_ds']['acs_reference_number'] ?? null,
            'acs_trans_id'         => $raw['three_ds']['acs_trans_id']         ?? null,
            'acs_signed_content'   => $raw['three_ds']['acs_signed_content']   ?? null,
            'acs_challenge_url'    => $raw['three_ds']['acs_challenge_url']    ?? $raw['three_ds']['challenge_value'] ?? null,
        ],
        'raw' => $raw,
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
