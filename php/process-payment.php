<?php

declare(strict_types=1);

/**
 * Card Payment Processing Script
 *
 * This script demonstrates card payment processing using the Global Payments SDK.
 * It handles tokenized card data and billing information to process payments
 * securely through the Global Payments API.
 *
 * PHP version 7.4 or higher
 *
 * @category  Payment_Processing
 * @package   GlobalPayments_Sample
 * @author    Global Payments
 * @license   MIT License
 * @link      https://github.com/globalpayments
 */

require_once 'vendor/autoload.php';

use Dotenv\Dotenv;
use GlobalPayments\Api\Entities\Address;
use GlobalPayments\Api\Entities\Exceptions\ApiException;
use GlobalPayments\Api\PaymentMethods\CreditCardData;
use GlobalPayments\Api\ServiceConfigs\Gateways\PorticoConfig;
use GlobalPayments\Api\ServicesContainer;

ini_set('display_errors', '0');

/**
 * Configure the SDK
 *
 * Sets up the Global Payments SDK with necessary credentials and settings
 * loaded from environment variables.
 *
 * @return void
 */
function configureSdk(): void
{
    $dotenv = Dotenv::createImmutable(__DIR__);
    $dotenv->load();

    $config = new PorticoConfig();
    $config->secretApiKey = $_ENV['SECRET_API_KEY'];
    $config->developerId = '000000';
    $config->versionNumber = '0000';
    $config->serviceUrl = 'https://cert.api2.heartlandportico.com';
    
    ServicesContainer::configureService($config);
}

/**
 * Sanitize postal code by removing invalid characters
 *
 * @param string|null $postalCode The postal code to sanitize
 *
 * @return string Sanitized postal code containing only alphanumeric
 *                characters and hyphens, limited to 10 characters
 */
function sanitizePostalCode(?string $postalCode): string
{
    if ($postalCode === null) {
        return '';
    }
    
    $sanitized = preg_replace('/[^a-zA-Z0-9-]/', '', $postalCode);
    return substr($sanitized, 0, 10);
}

// Initialize SDK configuration
configureSdk();

try {
    // Validate required fields
    if (!isset($_POST['payment_token'], $_POST['billing_zip'], $_POST['amount'])) {
        throw new ApiException('Missing required fields');
    }
    
    // Parse and validate amount
    $amount = floatval($_POST['amount']);
    if ($amount <= 0) {
        throw new ApiException('Invalid amount');
    }

    // Initialize payment data using tokenized card information
    $card = new CreditCardData();
    $card->token = $_POST['payment_token'];

    // Create billing address for AVS verification
    $address = new Address();
    $address->postalCode = sanitizePostalCode($_POST['billing_zip']);

    // Process the payment transaction with specified amount
    $response = $card->charge($amount)
        ->withAllowDuplicates(true)
        ->withCurrency('USD')
        ->withAddress($address)
        ->execute();
    
    // Verify transaction was successful
    if ($response->responseCode !== '00') {
        http_response_code(400);
        echo json_encode([
            'success' => false,
            'message' => 'Payment processing failed',
            'error' => [
                'code' => 'PAYMENT_DECLINED',
                'details' => $response->responseMessage
            ]
        ]);
        exit;
    }

    // Return success response with transaction ID
    echo json_encode([
        'success' => true,
        'message' => 'Payment successful! Transaction ID: ' . $response->transactionId,
        'data' => [
            'transactionId' => $response->transactionId
        ]
    ]);
} catch (ApiException $e) {
    // Handle payment processing errors
    http_response_code(400);
    echo json_encode([
        'success' => false,
        'message' => 'Payment processing failed',
        'error' => [
            'code' => 'API_ERROR',
            'details' => $e->getMessage()
        ]
    ]);
}
