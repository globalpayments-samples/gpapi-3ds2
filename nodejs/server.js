/**
 * Global Payments SDK Template - Node.js
 * 
 * This Express application provides a starting template for Global Payments SDK integration.
 * Customize the endpoints and logic below for your specific use case.
 */

import express from 'express';
import * as dotenv from 'dotenv';
import {
    ServicesContainer,
    PorticoConfig,
    Address,
    CreditCardData,
    ApiError
} from 'globalpayments-api';

// Load environment variables from .env file
dotenv.config();

/**
 * Initialize Express application with necessary middleware
 */
const app = express();
const port = process.env.PORT || 8000;

app.use(express.static('.')); // Serve static files
app.use(express.urlencoded({ extended: true })); // Parse form data
app.use(express.json()); // Parse JSON requests

// Configure Global Payments SDK with credentials and settings
const config = new PorticoConfig();
config.secretApiKey = process.env.SECRET_API_KEY;
config.serviceUrl = 'https://cert.api2.heartlandportico.com'; // Use production URL for live transactions
ServicesContainer.configureService(config);

/**
 * Utility function to sanitize postal code
 * Customize validation logic as needed for your use case
 */
const sanitizePostalCode = (postalCode) => {
    return postalCode.replace(/[^a-zA-Z0-9-]/g, '').slice(0, 10);
};

/**
 * Config endpoint - provides public API key for client-side use
 * Customize response data as needed
 */
app.get('/config', (req, res) => {
    res.json({
        success: true,
        data: {
            publicApiKey: process.env.PUBLIC_API_KEY
            // Add other configuration data as needed
        }
    });
});

/**
 * Example payment processing endpoint
 * Customize this endpoint for your specific payment flow
 */
app.post('/process-payment', async (req, res) => {
    try {
        // TODO: Add your payment processing logic here
        // Example implementation for basic charge:
        
        if (!req.body.payment_token) {
            throw new Error('Payment token is required');
        }

        const card = new CreditCardData();
        card.token = req.body.payment_token;

        // Customize amount and other parameters as needed
        const amount = req.body.amount || 10.00;

        // Add billing address if needed
        if (req.body.billing_zip) {
            const address = new Address();
            address.postalCode = sanitizePostalCode(req.body.billing_zip);
            
            const response = await card.charge(amount)
                .withAllowDuplicates(true)
                .withCurrency('USD')
                .withAddress(address)
                .execute();
                
            // Handle response...
            res.json({
                success: true,
                message: 'Payment processed successfully',
                data: { transactionId: response.transactionId }
            });
        } else {
            // Process without address
            const response = await card.charge(amount)
                .withAllowDuplicates(true)
                .withCurrency('USD')
                .execute();
                
            res.json({
                success: true,
                message: 'Payment processed successfully',
                data: { transactionId: response.transactionId }
            });
        }

    } catch (error) {
        res.status(500).json({
            success: false,
            message: 'Payment processing failed',
            error: error.message
        });
    }
});

/**
 * Add your custom endpoints here
 * Examples:
 * - app.post('/authorize', ...) // Authorization only
 * - app.post('/capture', ...)   // Capture authorized payment
 * - app.post('/refund', ...)    // Process refund
 * - app.get('/transaction/:id', ...) // Get transaction details
 */

// Start the server
app.listen(port, '0.0.0.0', () => {
    console.log(`Server running at http://localhost:${port}`);
    console.log(`Customize this template for your use case!`);
});