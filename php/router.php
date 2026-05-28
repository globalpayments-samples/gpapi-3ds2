<?php
/**
 * PHP built-in server router for GP-API 3DS2 backend.
 * Dispatches /api/* requests to the appropriate handler.
 */

$uri    = $_SERVER['REQUEST_URI'];
$method = $_SERVER['REQUEST_METHOD'];

// CORS preflight
if ($method === 'OPTIONS') {
    header('Access-Control-Allow-Origin: ' . (getenv('FRONTEND_ORIGIN') ?: 'http://localhost:8000'));
    header('Vary: Origin');
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

if ($path === '/3ds-method-notification') {
    notification_page('handleMethodNotification', $_POST['threeDSMethodData'] ?? $_GET['threeDSMethodData'] ?? '');
}

if ($path === '/3ds-challenge-notification') {
    notification_page('handleChallengeNotification', $_POST['cres'] ?? $_POST['CRes'] ?? $_GET['cres'] ?? $_GET['CRes'] ?? '');
}

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

function notification_page(string $handler, string $data): void
{
    $origin = getenv('FRONTEND_ORIGIN') ?: 'http://localhost:8000';
    $payload = encode_notification_payload($data);
    $nonce = base64_encode(random_bytes(16));
    header("Content-Security-Policy: default-src 'none'; script-src 'nonce-" . $nonce . "' https://cdn.jsdelivr.net; base-uri 'none'; frame-ancestors 'self'");
    header('X-Content-Type-Options: nosniff');
    header('Referrer-Policy: no-referrer');
    header('Content-Type: text/html; charset=utf-8');
    echo '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>3DS Notification</title>';
    echo '<script src="https://cdn.jsdelivr.net/npm/globalpayments-3ds@1.8.7/dist/globalpayments-3ds.min.js"></script>';
    echo '</head><body><script nonce="' . htmlspecialchars($nonce, ENT_QUOTES, 'UTF-8') . '">';
    echo 'const notificationData = atob(' . json_encode($payload) . ');';
    echo 'window.GlobalPayments?.ThreeDSecure?.' . $handler . '(notificationData, ' . json_encode($origin) . ');';
    echo '</script></body></html>';
    exit;
}

function encode_notification_payload(string $data): string
{
    if (strlen($data) > 12000 || !preg_match('/^[A-Za-z0-9+\/=_-]*$/', $data)) {
        return '';
    }
    return base64_encode($data);
}
