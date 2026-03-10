# PHP Card Payment Example

This example demonstrates card payment processing using PHP and the Global Payments SDK.

## Requirements

- PHP 7.4 or later
- Composer
- Global Payments account and API credentials

## Project Structure

- `process-payment.php` - Payment processing script
- `index.php` - Client-side payment form
- `composer.json` - Project dependencies
- `.env.sample` - Template for environment variables
- `run.sh` - Convenience script to run the application

## Setup

1. Clone this repository
2. Copy `.env.sample` to `.env`
3. Update `.env` with your Global Payments credentials:
   ```
   PUBLIC_API_KEY=pk_test_xxx
   SECRET_API_KEY=sk_test_xxx
   ```
4. Install dependencies:
   ```bash
   composer install
   ```
5. Run the application:
   ```bash
   ./run.sh
   ```
   Or manually:
   ```bash
   php -S localhost:8000
   ```

## Implementation Details

### Application Structure
The application uses a simple PHP structure:
- Static HTML form for payment collection
- Separate PHP script for payment processing
- Composer for dependency management

### SDK Configuration
Global Payments SDK configuration using environment variables:
- Loads credentials from .env file
- Sets up service URL for API communication
- Configures developer identification

### Payment Processing
Payment processing flow:
1. Client submits payment token and billing zip
2. Server creates CreditCardData with token
3. Creates Address with postal code
4. Processes $10 USD charge
5. Returns success/error response

### Error Handling
Implements comprehensive error handling:
- Catches and processes API exceptions
- Returns appropriate error messages
- Handles edge cases gracefully

## API Endpoints

### POST /process-payment.php
Processes a payment using the provided token and billing information.

Request Parameters:
- `payment_token` (string, required) - Token from client-side SDK
- `billing_zip` (string, required) - Billing postal code

Response (Success):
```
Payment successful! Transaction ID: xxx
```

Response (Error):
```
Error: [error message]
```

## Security Considerations

This example demonstrates basic implementation. For production use, consider:
- Implementing additional input validation
- Adding request rate limiting
- Including security headers
- Implementing proper logging
- Adding payment fraud prevention measures
- Using HTTPS in production
- Implementing CSRF protection
- Configuring proper session handling
- Setting appropriate PHP security directives
