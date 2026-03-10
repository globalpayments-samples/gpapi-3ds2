# Go Card Payment Example

This example demonstrates card payment processing using Go and the Global Payments SDK.

## Requirements

- Go 1.23 or later
- Global Payments account and API credentials

## Project Structure

- `main.go` - Server implementation with payment processing
- `static/index.html` - Client-side payment form
- `.env.sample` - Template for environment variables

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
   go mod download
   ```
5. Run the application:
   ```bash
   go run main.go
   ```
   The server will start on http://localhost:8888

## Implementation Details

### Application Structure
The application uses a simple Go structure:
- Static HTML form for payment collection
- RESTful API endpoints for configuration and payment processing
- Environment variable configuration

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
5. Returns success/error response as JSON

### Error Handling
Implements comprehensive error handling:
- Catches and processes API exceptions
- Returns appropriate error messages and HTTP status codes
- Handles edge cases gracefully

## API Endpoints

### GET /config
Returns the public API key for client-side SDK initialization.

Response:
```json
{
  "publicApiKey": "pk_test_xxx"
}
```

### POST /process-payment
Processes a payment using the provided token and billing information.

Request:
```json
{
  "payment_token": "token_from_client_sdk",
  "billing_zip": "12345"
}
```

Response (Success):
```json
{
  "message": "Payment successful! Transaction ID: xxx"
}
```

Response (Error):
```json
{
  "error": "Payment processing error: Invalid card data"
}
```

## Security Considerations

This example demonstrates basic implementation. For production use, consider:
- Implementing request rate limiting
- Adding security headers
- Implementing proper logging
- Adding payment fraud prevention measures
- Using HTTPS in production
- Implementing CSRF protection
- Setting appropriate security headers
- Proper error handling and logging
- Input validation and sanitization
