# PHP Backend

PHP 8.3+ server using the built-in CLI server (`php -S`). Dependencies managed by Composer — `vlucas/phpdotenv` only. GP-API calls are direct HTTP/cURL, while the browser uses Hosted Fields for single-use tokenization.

Runs on port **8080** internally (Docker host port **8003**).

---

## Files

```
router.php           Request dispatcher
src/GpApiClient.php  HTTP client + token cache (file-based, /tmp/gpapi_token.json)
api/
  health.php
  tokenization-config.php
  check-enrollment.php
  initiate-auth.php
  get-auth-result.php
  authorize-payment.php
composer.json
Dockerfile
.env.example
```

---

## Setup

```bash
cp .env.example .env
# fill in GP_APP_ID, GP_APP_KEY, GP_MERCHANT_ID, GP_ACCOUNT_NAME, GP_ACCOUNT_ID

composer install
php -S localhost:8080 router.php
```

---

## Environment Variables

```
GP_APP_ID=
GP_APP_KEY=
GP_MERCHANT_ID=
GP_ACCOUNT_NAME=transaction_processing
GP_ACCOUNT_ID=
GP_API_ENVIRONMENT=sandbox
GP_TOKENIZATION_ACCOUNT_NAME=
```

---

## Endpoints

```
GET  /api/health
GET  /api/tokenization-config
POST /api/check-enrollment
POST /api/initiate-auth
POST /api/get-auth-result
POST /api/authorize-payment
```

---

## Notes

- Token is cached to `/tmp/gpapi_token.json` and reloaded on subsequent requests.
- cURL is configured with `CURLOPT_ENCODING=''` to handle gzip responses from GP-API automatically.
- Dotenv loaded via `createUnsafeMutable` to allow overriding existing env vars.
- The `composer.lock` file is excluded from the Dockerfile COPY to avoid stale dependency issues.
