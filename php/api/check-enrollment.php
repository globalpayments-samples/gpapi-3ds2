<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createImmutable(__DIR__ . '/..');
$dotenv->load();

$input = json_decode(file_get_contents('php://input'), true) ?? [];

$cardNumber = $input['card_number'] ?? '';
$expMonth   = $input['exp_month']   ?? '';
$expYear    = $input['exp_year']    ?? '';

try {
    $raw = GpApiClient::request('POST', '/authentications', [
        'account_name' => getenv('GP_ACCOUNT_NAME') ?: 'transaction_processing',
        'channel'      => 'CNP',
        'country'      => 'GB',
        'amount'       => '1000',
        'currency'     => 'GBP',
        'reference'    => GpApiClient::uuid(),
        'payment_method' => [
            'entry_mode' => 'ECOM',
            'card' => [
                'number'       => $cardNumber,
                'expiry_month' => $expMonth,
                'expiry_year'  => GpApiClient::twoDigitYear($expYear),
            ],
        ],
        'three_ds' => [
            'source'          => 'BROWSER',
            'preference'      => 'NO_PREFERENCE',
            'message_version' => '2.2.0',
        ],
        'notifications' => [
            'challenge_return_url'       => getenv('CHALLENGE_NOTIFICATION_URL'),
            'three_ds_method_return_url' => getenv('METHOD_NOTIFICATION_URL'),
        ],
    ]);

    $methodUrl  = $raw['three_ds']['acs_info']['method_url'] ?? null;
    $methodData = null;
    if ($methodUrl) {
        $methodJson = json_encode([
            'threeDSServerTransID'  => $raw['id'],
            'methodNotificationURL' => getenv('METHOD_NOTIFICATION_URL'),
        ]);
        $methodData = base64_encode($methodJson);
    }

    GpApiClient::jsonResponse([
        'success' => true,
        'data' => [
            'server_trans_id' => $raw['id'],
            'enrolled'        => $raw['three_ds']['enrolled'] ?? null,
            'method_url'      => $methodUrl,
            'method_data'     => $methodData,
        ],
        'raw' => $raw,
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
