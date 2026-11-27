# GP-API 3DS2 Sandbox

> Run the GP-API 3DS2 browser authentication flow with the official Global Payments SDK in Node.js, PHP, Java and .NET backends, behind one shared browser page that tokenizes the card with Hosted Fields.

## Critical Patterns

1. **Every GP-API call goes through the official SDK.** Each backend builds a `GpApiConfig` from the environment (`sdkConfig()` in `nodejs/gp-sdk.js`, `GpApiClient::sdkConfig()` in PHP, `sdkConfig()` in `GpApi3dsServlet.java`, `SdkConfig()` in `dotnet/Program.cs`), registers it once under the name `gpapi-3ds-sample`, and uses `Secure3dService.checkEnrollment`, `initiateAuthentication` and `getAuthenticationData`, then `CreditCardData.charge` for the sale. There is no hand built `/ucp` request and no manual token code; the SDK creates and caches the access token.
2. **The Hosted Fields token comes from a second config with `PMT_POST_Create_Single`.** `GET /api/tokenization-config` calls `GpApiService.generateTransactionKey` with the tokenization flag set (permission `PMT_POST_Create_Single`, ten minute expiry) and returns `env`, `accessToken`, `accountName`, `merchantId` (not in Node) and `apiVersion`. The page passes the resulting `PMT_...` id to every later call as `payment_method_id`.
3. **Keep the full `AUT_` id.** `server_trans_id` from `/api/check-enrollment` goes back unchanged as `server_trans_id` to `/api/initiate-auth` and `/api/get-auth-result`, and as `authentication_id` to `/api/authorize-payment`. The SDK puts it in the `/authentications/{id}/initiate` and `/result` paths and in `payment_method.authentication.id` on the sale.
4. **The sale attaches 3DS by fetching the authentication again.** `/api/authorize-payment` reads `authentication_id` (or `three_ds.authentication_id`, or `three_ds.server_trans_ref`), calls `getAuthenticationData` for it and sets the result as the card's `threeDSecure` before charging. The other `three_ds` fields the page sends are not used.
5. **Final statuses are `SUCCESS_AUTHENTICATED` and `SUCCESS_ATTEMPT_MADE`.** The page only charges after one of those, and `test-all-cards.sh` uses the same pair. `CHALLENGE_REQUIRED` opens the ACS challenge through `globalpayments-3ds`; `AVAILABLE` after initiate means the method step has not finished and the page stops.
6. **Use `.env.example`, not `.env.sample`.** The `.env.example` files hold the variables the code reads. The `.env.sample` files are older and use other names.

## Repository Structure

### Node.js (Express, Node 24)
- [`nodejs/server.js`](nodejs/server.js): routes, CORS for `FRONTEND_ORIGIN`, rate limits on `/api` and the callbacks, error mapping, notification pages
- [`nodejs/gp-sdk.js`](nodejs/gp-sdk.js): SDK config, `hostedFieldsToken()`, `checkEnrollment()`, `initiateAuthentication()`, `getAuthenticationData()`, `charge()` and the `map*` response helpers
- [`nodejs/package.json`](nodejs/package.json): `globalpayments-api` 3.10, `express` 4.18, `express-rate-limit` 7.5, `escape-html`, `dotenv`

### PHP (built-in server, PHP 8.2.35+, 8.3.36+, 8.4.27+ or 8.5.12+)
- [`php/router.php`](php/router.php): routes the six API paths and both callbacks, returns 404 for anything else so `.env`, composer files and `vendor/` are never served
- [`php/api/`](php/api/): one file per route (`health.php`, `tokenization-config.php`, `check-enrollment.php`, `initiate-auth.php`, `get-auth-result.php`, `authorize-payment.php`)
- [`php/src/GpApiClient.php`](php/src/GpApiClient.php): SDK config and calls, input mapping, `map*` helpers, JSON responses
- [`php/composer.json`](php/composer.json): `globalpayments/php-sdk` 13.3, `vlucas/phpdotenv` 5.5

### Java (Jakarta Servlet on Tomcat through Cargo, Java 21)
- [`java/src/main/java/com/globalpayments/example/GpApi3dsServlet.java`](java/src/main/java/com/globalpayments/example/GpApi3dsServlet.java): every route, `sdkConfig()`, `handleCheckEnrollment()`, `handleInitiateAuth()`, `handleGetAuthResult()`, `handleAuthorizePayment()`, notification pages
- [`java/pom.xml`](java/pom.xml): `globalpayments-sdk` 15.1.14, Jackson 2.18.11, `dotenv-java` 3.0.0, Jakarta Servlet 5.0.0, Cargo 1.10.10; Cargo listens on `gpapi.cargo.port` (8004)

### .NET (ASP.NET Core minimal API, .NET 10)
- [`dotnet/Program.cs`](dotnet/Program.cs): every route, `SdkConfig()`, `PaymentMethod()`, `BrowserDataFrom()`, `Map*` helpers, `SdkError()`, notification pages; listens on `GP_SAMPLE_PORT`, then `PORT`, then 8080
- [`dotnet/dotnet.csproj`](dotnet/dotnet.csproj): `GlobalPayments.Api` 11.0.1, `DotEnv.Net` 3.2.1

### Shared
- [`index.html`](index.html): the only copy of the page; served on its own (port 8000) and talks to the backend picked in its dropdown. `initHostedFields()`, `runFlow()`, `runApiFlow()`, `runMethodUrl()` and `openAcsModal()` drive the flow, and a raw card diagnostic mode skips Hosted Fields
- [`run.sh`](run.sh): `check`, `setup`, `dev <backend>`, `smoke <backend|all>` and `docker` helpers
- [`test-all-cards.sh`](test-all-cards.sh): CLI smoke test with raw card data: health, tokenization config, enrollment, initiate, auth result and a sale for every card that reaches a final status
- [`docker-compose.yml`](docker-compose.yml): frontend `:8000`, Node `:3001`, PHP `:8003`, Java `:8004`, .NET `:8006`

## API Surface

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health` | `{"status":"ok","backend":"...","version":"1.0.0"}`, Node, Java and .NET also return `sdk` |
| GET | `/api/tokenization-config` | Hosted Fields access token and account details |
| POST | `/api/check-enrollment` | `payment_method_id` (or raw card fields); returns `server_trans_id`, `enrolled`, `message_version`, `method_url`, `method_data`, `server_trans_ref` |
| POST | `/api/initiate-auth` | `server_trans_id`, `message_version`, `method_url_completion`, `browser_data`, `order`; returns `status`, the ACS fields and, for `CHALLENGE_REQUIRED`, a `challenge` object with `requestUrl` and `encodedChallengeRequest` |
| POST | `/api/get-auth-result` | `server_trans_id`; returns `status`, `eci`, `authentication_value`, `ds_trans_ref`, `message_version` |
| POST | `/api/authorize-payment` | `payment_method_id`, `amount`, `currency`, `authentication_id`; returns `transaction_id`, `status`, `result_code`, plus `amount` and `currency` in Node, PHP and Java |
| GET, POST | `/3ds-method-notification`, `/3ds-challenge-notification` | callback pages that hand the result to `globalpayments-3ds` in the parent page at `FRONTEND_ORIGIN` |

Responses are `{"success":true,"data":{...},"raw":{...}}` or `{"success":false,"error":"...","raw":{...}}`. The values in `data` come from the SDK of each language, so `result_code` is `SUCCESS` in Node and PHP and `00` in Java and .NET.

## Environment Variables

```bash
GP_APP_ID=your_app_id_here
GP_APP_KEY=your_app_key_here
GP_API_ENVIRONMENT=sandbox                 # production switches the SDK to production
GP_ACCOUNT_NAME=transaction_processing     # must match the portal exactly
GP_ACCOUNT_ID=                             # TRA_ id of that account, leave blank if unknown
GP_TOKENIZATION_ACCOUNT_NAME=              # optional, defaults to the one in the token response
GP_PARTNER_MERCHANT_ID=                    # partner mode only
GP_COUNTRY=GB                              # optional, defaults to GB
GP_MERCHANT_CONTACT_URL=                   # optional, defaults to https://developer.globalpay.com/
METHOD_NOTIFICATION_URL=https://<public-host>/3ds-method-notification
CHALLENGE_NOTIFICATION_URL=https://<public-host>/3ds-challenge-notification   # must be HTTPS
FRONTEND_ORIGIN=http://localhost:8000
PORT=3001                                  # Node; PHP run.sh also reads PORT; .NET reads GP_SAMPLE_PORT or PORT
```

Each backend reads its own `<language>/.env`. A placeholder left in `GP_ACCOUNT_ID` is sent to GP-API as is, so clear it when there is no real `TRA_` id.

## Test Cards

Expiry `12/2026`, CVV `123`. The README table has the expected results. Attempted authentication: `4222000005218627` (`SUCCESS_ATTEMPT_MADE`, ECI 06).

## Security Notes

Demo code. CORS is limited to `FRONTEND_ORIGIN` but there is no authentication on the API routes, raw SDK objects go back to the browser under `raw`, payer and address data is hardcoded, and credentials live in `.env` files. The callback pages send a CSP with a per request nonce and only accept base64 payloads.

## How to Run

```bash
cp nodejs/.env.example nodejs/.env    # same for php, java, dotnet
./run.sh setup node
./run.sh dev node                     # page on :8000, backend on its port
./run.sh smoke all                    # test-all-cards.sh against each backend
./run.sh docker up                    # all services from docker-compose.yml
```

The full browser flow needs both notification URLs on a public HTTPS host. The smoke test does not run the method iframe or the ACS challenge.

## Making Changes

All four backends expose the same routes and request fields. A change to one goes into all four, each language in its own commit. `index.html`, `run.sh`, `test-all-cards.sh` and `docker-compose.yml` are shared. Do not add a new language folder without being asked.
