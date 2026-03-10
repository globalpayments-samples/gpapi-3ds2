<?php

declare(strict_types=1);

/**
 * Configuration Endpoint
 *
 * This script provides configuration information for the client-side SDK,
 * including the public API key needed for tokenization.
 *
 * PHP version 7.4 or higher
 *
 * @category  Configuration
 * @package   GlobalPayments_Sample
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;

try {
    // Load environment variables from .env file
    $dotenv = Dotenv::createImmutable(__DIR__);
    $dotenv->load();

    // Set response content type to JSON
    header('Content-Type: application/json');

    // Return public API key in JSON response
    echo json_encode([
        'success' => true,
        'data' => [
            'publicApiKey' => $_ENV['PUBLIC_API_KEY'],
        ],
    ]);
} catch (Exception $e) {
    // Handle configuration errors
    http_response_code(500);
    echo json_encode([
        'success' => false,
        'message' => 'Error loading configuration: ' . $e->getMessage()
    ]);
}
