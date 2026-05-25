<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createUnsafeMutable(__DIR__ . '/..');
$dotenv->load();

$input         = json_decode(file_get_contents('php://input'), true) ?? [];
$serverTransIdRaw = $input['server_trans_id'] ?? '';
$serverTransId    = trim((string) $serverTransIdRaw);

if (!$serverTransId) {
    GpApiClient::jsonResponse(['success' => false, 'error' => 'server_trans_id is required'], 400);
}

try {
    $secure = GpApiClient::getAuthenticationData($serverTransId, (string)($input['amount'] ?? '10.00'));

    GpApiClient::jsonResponse([
        'success' => true,
        'data' => GpApiClient::mapAuthentication($secure),
        'raw' => $secure,
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
