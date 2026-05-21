<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createUnsafeMutable(__DIR__ . '/..');
$dotenv->load();

try {
    $token = GpApiClient::getTokenizationAccessTokenData();
    $tokenizationAccount = $token['scope']['accounts'][0]['name'] ?? null;
    GpApiClient::jsonResponse([
        'success' => true,
        'data' => [
            'env' => getenv('GP_API_ENVIRONMENT') ?: 'sandbox',
            'accessToken' => $token['token'],
            'accountName' => getenv('GP_TOKENIZATION_ACCOUNT_NAME') ?: ($tokenizationAccount ?: (getenv('GP_ACCOUNT_NAME') ?: 'transaction_processing')),
            'merchantId' => getenv('GP_MERCHANT_ID') ?: null,
            'apiVersion' => '2021-03-22',
        ],
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
