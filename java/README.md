# Java Card Payment Example

This example demonstrates card payment processing using Jakarta EE and the Global Payments SDK.

## Requirements

- Java 11 or later
- Maven
- Global Payments account and API credentials

## Project Structure

- `src/main/java/com/globalpayments/example/ProcessPaymentServlet.java` - Main servlet handling payment processing
- `src/main/webapp/index.html` - Client-side payment form
- `src/main/webapp/WEB-INF/web.xml` - Web application configuration
- `.env.sample` - Template for environment variables
- `pom.xml` - Project dependencies and build configuration
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
   mvn clean install
   ```
5. Run the application:
   ```bash
   ./run.sh
   ```
   Or manually:
   ```bash
   mvn jetty:run
   ```

## Implementation Details

### Servlet Configuration
The application uses Jakarta EE servlets to:
- Handle payment processing requests
- Serve configuration data
- Process form submissions

### SDK Configuration
Global Payments SDK configuration is handled in the servlet's init method:
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
- Returns appropriate HTTP status codes
- Provides meaningful error messages

## API Endpoints

### GET /public-key
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
Error: [error message]
```

## Security Considerations

This example demonstrates basic implementation. For production use, consider:
- Implementing additional input validation
- Adding request rate limiting
- Including security headers
- Implementing proper logging
- Adding payment fraud prevention measures
- Configuring secure session management
