<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createUnsafeMutable(__DIR__ . '/..');
$dotenv->load();

$input      = json_decode(file_get_contents('php://input'), true) ?? [];
$cardNumber = $input['card_number'] ?? '';
$expMonth   = $input['exp_month']   ?? '';
$expYear    = $input['exp_year']    ?? '';
$cvn        = $input['cvn']         ?? '';
$amount     = $input['amount']      ?? '10.00';
$currency   = $input['currency']    ?? 'GBP';
$threeDsIn  = $input['three_ds']    ?? [];

try {
    $raw = GpApiClient::request('POST', '/transactions', [
        'account_name' => getenv('GP_ACCOUNT_NAME') ?: 'transaction_processing',
        'channel'      => 'CNP',
        'type'         => 'SALE',
        'amount'       => GpApiClient::toMinorUnits($amount),
        'currency'     => $currency,
        'reference'    => GpApiClient::uuid(),
        'country'      => 'GB',
        'payment_method' => [
            'entry_mode' => 'ECOM',
            'card' => [
                'number'       => $cardNumber,
                'expiry_month' => $expMonth,
                'expiry_year'  => GpApiClient::twoDigitYear($expYear),
                'cvv'          => $cvn,
            ],
        ],
        'three_ds' => [
            'source'               => 'BROWSER',
            'authentication_value' => $threeDsIn['authentication_value'] ?? null,
            'server_trans_ref'     => $threeDsIn['server_trans_ref']     ?? null,
            'ds_trans_ref'         => $threeDsIn['ds_trans_ref']         ?? null,
            'eci'                  => $threeDsIn['eci']                  ?? null,
            'message_version'      => $threeDsIn['message_version']      ?? '2.2.0',
        ],
    ]);

    GpApiClient::jsonResponse([
        'success' => true,
        'data' => [
            'transaction_id' => $raw['id'],
            'status'         => $raw['status']                ?? null,
            'result_code'    => $raw['action']['result_code'] ?? null,
            'amount'         => $raw['amount']                ?? null,
            'currency'       => $raw['currency']              ?? null,
        ],
        'raw' => $raw,
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
