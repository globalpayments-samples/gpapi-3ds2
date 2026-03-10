# Node.js Card Payment Example

This example demonstrates card payment processing using Express.js and the Global Payments SDK.

## Requirements

- Node.js 14.x or later
- npm (Node Package Manager)
- Global Payments account and API credentials

## Project Structure

- `server.js` - Main application file containing server setup and payment processing
- `index.html` - Client-side payment form
- `package.json` - Project dependencies and scripts
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
   npm install
   ```
5. Run the application:
   ```bash
   ./run.sh
   ```
   Or manually:
   ```bash
   node server.js
   ```

## Implementation Details

### Server Setup
The application uses Express.js to create a web server that:
- Serves static files
- Processes payment requests
- Provides configuration endpoint for client-side SDK
- Handles JSON and form-encoded requests

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
- Differentiates between API and general errors
- Returns appropriate error messages

## API Endpoints

### GET /config
Returns public API key for client-side SDK initialization.

Response:
```json
{
    "publicApiKey": "pk_test_xxx"
}
```

### POST /process-payment
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
API Error: [error message]
```
or
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
- Configuring Cross-Origin Resource Sharing (CORS) appropriately
