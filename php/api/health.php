<?php
declare(strict_types=1);
require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/GpApiClient.php';

header('Content-Type: application/json');
header('Access-Control-Allow-Origin: *');
echo json_encode(['status' => 'ok', 'backend' => 'php', 'version' => '1.0.0']);
