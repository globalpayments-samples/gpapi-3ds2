<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

use Dotenv\Dotenv;
$dotenv = Dotenv::createUnsafeMutable(__DIR__ . '/..');
$dotenv->load();

try {
    GpApiClient::jsonResponse([
        'success' => true,
        'data' => GpApiClient::tokenizationConfig(),
    ]);
} catch (\Throwable $e) {
    GpApiClient::errorResponse($e);
}
