<?php
/**
 * PHP built-in server router for GP-API 3DS2 backend.
 * Dispatches /api/* requests to the appropriate handler.
 */

$uri    = $_SERVER['REQUEST_URI'];
$method = $_SERVER['REQUEST_METHOD'];

// CORS preflight
if ($method === 'OPTIONS') {
    header('Access-Control-Allow-Origin: *');
    header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type');
    http_response_code(204);
    exit;
}

$routes = [
    'GET /api/health'             => __DIR__ . '/api/health.php',
    'GET /api/tokenization-config'=> __DIR__ . '/api/tokenization-config.php',
    'POST /api/check-enrollment'  => __DIR__ . '/api/check-enrollment.php',
    'POST /api/initiate-auth'     => __DIR__ . '/api/initiate-auth.php',
    'POST /api/get-auth-result'   => __DIR__ . '/api/get-auth-result.php',
    'POST /api/authorize-payment' => __DIR__ . '/api/authorize-payment.php',
];

// Strip query string
$path = strtok($uri, '?');
$key  = $method . ' ' . $path;

if (isset($routes[$key])) {
    require $routes[$key];
    exit;
}

// 404 for unmatched API routes
if (str_starts_with($path, '/api/')) {
    header('Content-Type: application/json');
    http_response_code(404);
    echo json_encode(['error' => 'Not found', 'path' => $path]);
    exit;
}

// Serve static files (index.html etc.) for non-API routes
return false;
