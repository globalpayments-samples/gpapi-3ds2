# GP-API 3DS2 Sandbox

> Run the GP-API 3DS2 browser authentication flow through direct UCP HTTP calls across Node.js, PHP, Java, and .NET backends.

## Critical Patterns

1. **Build the access-token secret as `SHA512(nonce + GP_APP_KEY)` and send `X-GP-Version: 2021-03-22`.** `nodejs/auth.js` (`generateToken()`), `php/src/GpApiClient.php` (`generateToken()`), `java/src/main/java/com/globalpayments/example/GpApi3dsServlet.java` (`generateToken()`), and `dotnet/Program.cs` (`GetAccessTokenAsync()`) all post that exact shape to `/ucp/accesstoken`. If the hash formula or version header changes, every downstream 3DS call fails before enrollment starts.
2. **Strip an `AUT_` prefix before reusing `server_trans_id`.** `php/api/initiate-auth.php`, `php/api/get-auth-result.php`, `java/src/main/java/com/globalpayments/example/GpApi3dsServlet.java` (`handleInitiateAuth()`, `handleGetAuthResult()`), `dotnet/Program.cs` (inline `app.MapPost` handlers, line ~169 and line ~259), and `nodejs/server.js` (inline `app.post` handlers, line ~145 and line ~245) all normalize the ID before re-posting auth data or fetching `/authentications/{id}`.
3. **Replay the completed 3DS values when authorizing the sale.** Every backend sends `authentication_value`, `server_trans_ref`, `ds_trans_ref`, `eci`, and `message_version` from `/api/get-auth-result` into `/ucp/transactions`; omitting any of them turns the final payment request into a non-3DS sale.
4. **Use `.env.example` as the setup source, not `.env.sample`.** The checked-in `.env.example` files match the variables the code reads; the parallel `.env.sample` files still contain older placeholder keys and will not boot the 3DS2 flow correctly if copied verbatim.

## Repository Structure

### Node.js (Express, Node 18+)
- [`nodejs/auth.js`](nodejs/auth.js) — reference token helper; `generateToken()` and `getAccessToken()` cache OAuth tokens for all GP-API calls.
- [`nodejs/server.js`](nodejs/server.js) — Node implementation; `gpRequest()`, `toMinorUnits()`, `twoDigitYear()`, and inline route handlers at line ~82, ~90, ~145, ~245, and ~281 implement the public API.
- [`nodejs/run.sh`](nodejs/run.sh) — installs dependencies and starts `npm start`.
- [`nodejs/.env.example`](nodejs/.env.example) — accurate Node env template.

### PHP (CLI server, PHP 8.1+)
- [`php/src/GpApiClient.php`](php/src/GpApiClient.php) — PHP reference client; `getAccessToken()`, `generateToken()`, `request()`, `toMinorUnits()`, and `errorResponse()` wrap direct UCP calls.
- [`php/router.php`](php/router.php) — request dispatcher for all five public routes.
- [`php/api/check-enrollment.php`](php/api/check-enrollment.php) — enrollment request and method-data response.
- [`php/api/initiate-auth.php`](php/api/initiate-auth.php) — browser-data auth request and ACS challenge handoff.
- [`php/api/get-auth-result.php`](php/api/get-auth-result.php) — final authentication lookup.
- [`php/api/authorize-payment.php`](php/api/authorize-payment.php) — `/transactions` sale using returned 3DS fields.
- [`php/run.sh`](php/run.sh) — convenience launcher; if you need routed `/api/*` requests outside Docker, follow the README command with `router.php`.
- [`php/config.php`](php/config.php) and [`php/process-payment.php`](php/process-payment.php) — legacy Portico/tokenization files; they are not routed by `router.php`.

### Java (Servlet + embedded Tomcat/Cargo, Java 21)
- [`java/src/main/java/com/globalpayments/example/GpApi3dsServlet.java`](java/src/main/java/com/globalpayments/example/GpApi3dsServlet.java) — Java reference implementation; `handleCheckEnrollment()`, `handleInitiateAuth()`, `handleGetAuthResult()`, `handleAuthorizePayment()`, `gpRequest()`, `getAccessToken()`, and `generateToken()` cover the full flow.
- [`java/pom.xml`](java/pom.xml) — dependency manifest plus Cargo/Tomcat port configuration.
- [`java/run.sh`](java/run.sh) — builds and runs Cargo/Tomcat.
- [`java/.env.example`](java/.env.example) — accurate Java env template.

### .NET (ASP.NET Core Minimal API, .NET 9)
- [`dotnet/Program.cs`](dotnet/Program.cs) — .NET implementation; `GetAccessTokenAsync()`, `GpRequest()`, `GpError()`, `ToMinorUnits()`, `TwoDigitYear()`, and inline `app.MapGet`/`app.MapPost` handlers at line ~108, ~110, ~169, ~259, and ~290 expose the API.
- [`dotnet/dotnet.csproj`](dotnet/dotnet.csproj) — minimal package manifest.
- [`dotnet/run.sh`](dotnet/run.sh) — restores packages and starts `dotnet run`.
- [`dotnet/.env.example`](dotnet/.env.example) — accurate .NET env template.

### Shared
- [`README.md`](README.md) — runtime matrix, sandbox caveats, and test-card overview.
- [`index.html`](index.html) — shared browser UI; `backendUrl()`, `checkHealth()`, `api()`, `runMethodUrl()`, `openAcsModal()`, and `runFlow()` drive the 3DS2 browser flow.
- [`docker-compose.yml`](docker-compose.yml) — multi-service launcher exposing frontend `:8000`, Node `:8001`, PHP `:8003`, Java `:8004`, and .NET `:8006`.
- [`test-all-cards.sh`](test-all-cards.sh) — CLI smoke test for `/api/health`, `/api/check-enrollment`, and `/api/initiate-auth` only; browser-only steps remain unverified.

## API Surface

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | Return backend name and readiness. |
| POST | `/api/check-enrollment` | Create the initial `/authentications` request and return enrollment plus 3DS method data. |
| POST | `/api/initiate-auth` | Submit browser data, card details, and order data to continue authentication. |
| POST | `/api/get-auth-result` | Fetch the completed authentication result for a `server_trans_id`. |
| POST | `/api/authorize-payment` | Submit a `SALE` to `/ucp/transactions` using the completed 3DS fields. |

All four language implementations expose the same five routes.

## Environment Variables

```bash
GP_APP_ID=your_app_id_here                         # GP-API application ID for /ucp/accesstoken
GP_APP_KEY=your_app_key_here                       # GP-API application key hashed into the token secret
GP_MERCHANT_ID=MER_your_merchant_id_here           # Merchant ID added to auth payloads when present
GP_ACCOUNT_ID=TRA_your_account_id_here             # Optional TRA_ account ID for the target channel
GP_ACCOUNT_NAME=transaction_processing             # Case-sensitive UCP account name
METHOD_NOTIFICATION_URL=https://developer.globalpayments.com/3ds2/method-notification
CHALLENGE_NOTIFICATION_URL=https://developer.globalpayments.com/3ds2/challenge-notification
PORT=3001|8080                                     # Node default 3001; .NET default 8080; PHP launcher also reads this
```

Use each language's `.env.example` as the source of truth. Node falls back to `3001` in `nodejs/server.js`, .NET falls back to `8080` in `dotnet/Program.cs`, Java stays on Cargo port `8080` from `java/pom.xml`, and PHP's effective port comes from the command you run (`php/run.sh` defaults to `8000`, while the README's routed example uses `8080`).

## Test Cards / Sandbox Credentials

| Value | Purpose |
|---|---|
| `4263970000005262` | GP-API frictionless Visa |
| `5425230000004415` | GP-API frictionless Mastercard |
| `4012001037141112` | Challenge-required Visa |
| `5114610000004778` | Challenge-required Mastercard |
| `4012001036853337` | Authentication-failed Visa |
| `4012001036273338` | Unavailable / not-enrolled Visa |
| `12/2026`, `CVV 123` | Shared expiry and CVV for all sandbox cards |

Get sandbox app and merchant credentials from https://developer.globalpayments.com/.

## API Request Shape

- `POST https://apis.sandbox.globalpay.com/ucp/accesstoken` — send JSON with `app_id`, `nonce`, `secret`, and `grant_type=client_credentials`, plus `X-GP-Version: 2021-03-22`.
- `secret` — hex SHA-512 of `nonce + GP_APP_KEY`; there is no separator and no merchant ID in the token request.
- `three_ds.source=BROWSER` and `payment_method.entry_mode=ECOM` — fixed across enrollment, initiate-auth, and authorize-payment requests.
- `three_ds.method_url_completion=UNAVAILABLE` — the headless smoke test uses this because it cannot run the browser method callback.
- `payer.billing_address.country=826` — numeric ISO code, not `GB`.
- `POST /ucp/transactions` — must replay `authentication_value`, `server_trans_ref`, `ds_trans_ref`, `eci`, and `message_version` from the completed auth result.

## Architecture Summary

**Browser flow:** `index.html` → selected backend `/api/check-enrollment` → optional method URL / ACS challenge → `/api/initiate-auth` → `/api/get-auth-result` → `/api/authorize-payment`.
**Backend flow:** route handler → token helper/cache → direct GP-API UCP request → normalized JSON back to the frontend.
**CLI smoke test:** `test-all-cards.sh` → `/api/health` + `/api/check-enrollment` + `/api/initiate-auth`; challenge completion and final auth values still require a browser.

## Security Notes

This is demo code. Every backend enables permissive CORS, returns raw GP-API payloads to the browser, hardcodes sandbox payer/address defaults, and skips application-level auth and secret-manager integration. PHP also caches tokens in a local file instead of protected storage.

## How to Run

```bash
# Recommended: all services together
cd /path/to/gpapi-3ds2
cp nodejs/.env.example nodejs/.env
cp php/.env.example php/.env
cp java/.env.example java/.env
cp dotnet/.env.example dotnet/.env
docker compose up --build

# Individual backends
cd nodejs && ./run.sh                         # Node.js -> http://localhost:3001
cd java && ./run.sh                           # Java -> http://localhost:8080 (host 8004 via Docker)
cd dotnet && ./run.sh                         # .NET -> http://localhost:8080
cd php && composer install && php -S localhost:8080 router.php
```

Use a real browser for the full 3DS2 flow. The CLI smoke test cannot execute the method iframe or ACS challenge.

## How to Verify

```bash
BASE=http://localhost:3001

# Health
curl "$BASE/api/health"
# Expected: {"status":"ok","backend":"nodejs|php|java|dotnet","version":"1.0.0"}

# Enrollment
curl -X POST "$BASE/api/check-enrollment" \
  -H "Content-Type: application/json" \
  -d '{"card_number":"4263970000005262","exp_month":"12","exp_year":"2026"}'
# Expected: {"success":true,"data":{"server_trans_id":"AUT_...","enrolled":"ENROLLED",...}}

# Initiate auth (replace SERVER_TRANS_ID with the previous response value)
curl -X POST "$BASE/api/initiate-auth" \
  -H "Content-Type: application/json" \
  -d '{"server_trans_id":"SERVER_TRANS_ID","message_version":"2.2.0","method_url_completion":"UNAVAILABLE","card_number":"4263970000005262","exp_month":"12","exp_year":"2026","cardholder_name":"Test User","browser_data":{"accept_header":"text/html","color_depth":24,"ip":"123.123.123.123","java_enabled":false,"javascript_enabled":true,"language":"en-GB","screen_height":1080,"screen_width":1920,"challenge_window_size":"FULL_SCREEN","timezone":"0","user_agent":"curl-test"},"order":{"amount":"10.00","currency":"GBP"}}'
# Expected: {"success":true,"data":{"status":"AVAILABLE",...}}

# Auth result (use the same SERVER_TRANS_ID after a real browser challenge)
curl -X POST "$BASE/api/get-auth-result" \
  -H "Content-Type: application/json" \
  -d '{"server_trans_id":"SERVER_TRANS_ID"}'
# Expected after browser completion: {"success":true,"data":{"eci":"...","authentication_value":"...",...}}

# Authorize payment (requires 3DS fields returned by /api/get-auth-result)
curl -X POST "$BASE/api/authorize-payment" \
  -H "Content-Type: application/json" \
  -d '{"card_number":"4263970000005262","exp_month":"12","exp_year":"2026","cvn":"123","amount":"10.00","currency":"GBP","three_ds":{"authentication_value":"AUTH_VALUE","server_trans_ref":"SERVER_TRANS_REF","ds_trans_ref":"DS_TRANS_REF","eci":"05","message_version":"2.2.0"}}'
# Expected after a completed browser flow: {"success":true,"data":{"transaction_id":"TRN_...","status":"CAPTURED|FUNDED|DECLINED",...}}

# Headless smoke test for all cards
./test-all-cards.sh 3001 nodejs
# Expected: health + enrollment + initiate-auth pass; browser-only steps remain skipped
```

A real browser is required to run the method iframe and ACS challenge. Without that browser step, `/api/get-auth-result` and `/api/authorize-payment` will not return a fully authenticated 3DS result.

## Making Changes

All language implementations expose identical behavior. If you change the request or response contract in one backend, replicate it in Node.js, PHP, Java, and .NET in separate commits. Treat `docker-compose.yml`, `README.md`, `test-all-cards.sh`, and the root `index.html` as shared files, and remember that frontend copies also exist in `nodejs/index.html`, `php/index.html`, `java/src/main/webapp/index.html`, and `dotnet/wwwroot/index.html`. This repo implements only four languages today; do not add a new language folder without explicit instruction.

## SDK Versions

- Node.js: `express` ^4.18.2, `dotenv` ^16.3.1
- PHP: `vlucas/phpdotenv` ^5.5
- Java: `jackson-databind` 2.17.0, `dotenv-java` 3.0.0, `jakarta.servlet-api` 5.0.0, Cargo Tomcat plugin 1.10.10
- .NET: `DotEnv.Net` 3.2.1
- GP SDK usage: none; every backend calls GP-API directly over HTTP
