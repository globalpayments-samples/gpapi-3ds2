<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createUnsafeMutable(__DIR__ . '/..');
$dotenv->load();

$input      = json_decode(file_get_contents('php://input'), true) ?? [];
$threeDsIn  = $input['three_ds']    ?? [];
$authenticationId = $input['authentication_id'] ?? ($threeDsIn['authentication_id'] ?? ($threeDsIn['server_trans_ref'] ?? null));

try {
    $secure = $authenticationId ? GpApiClient::getAuthenticationData((string)$authenticationId, (string)($input['amount'] ?? '10.00')) : null;
    $transaction = GpApiClient::charge($input, $secure);

    GpApiClient::jsonResponse([
        'success' => true,
        'data' => GpApiClient::mapTransaction($transaction),
        'raw' => $transaction,
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
