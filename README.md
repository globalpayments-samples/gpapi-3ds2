# GP-API 3DS2 Sandbox

Sample 3DS2 checkout flow for Global Payments UCP.

The browser uses Global Payments Hosted Fields to create a single-use payment token. The selected backend then uses the official Global Payments SDK for enrollment, authentication, result lookup, and payment authorization.

Backends included:

```
nodejs/   Node.js + Express
php/      PHP + built-in CLI server
dotnet/   ASP.NET Core minimal API
java/     Jakarta servlet + Tomcat via Maven Cargo
```

A raw-card diagnostic mode is still available in the UI for sandbox troubleshooting. Use Hosted Fields for the normal browser path.

## Quick Start

Copy the env files and add credentials:

```bash
cp nodejs/.env.example nodejs/.env
cp php/.env.example php/.env
cp dotnet/.env.example dotnet/.env
cp java/.env.example java/.env
```

Run one backend locally:

```bash
./run.sh setup node
./run.sh dev node
```

Open `http://localhost:8000`, choose the matching backend in the dropdown, and run the Hosted Fields flow.

Available backend names are `node`, `php`, `dotnet`, and `java`.

## Docker

```bash
./run.sh docker up
```

Services:

| Service | URL |
| --- | --- |
| Frontend | `http://localhost:8000` |
| Node.js | `http://localhost:3001` |
| PHP | `http://localhost:8003` |
| Java | `http://localhost:8004` |
| .NET | `http://localhost:8006` |

Stop Docker services:

```bash
./run.sh docker down
```

## Verification

Run build and syntax checks:

```bash
./run.sh check
```

Run real sandbox smoke tests:

```bash
./run.sh smoke node
./run.sh smoke all
./run.sh docker smoke
```

The smoke test covers backend health, Hosted Fields token configuration, enrollment, initiate-auth, auth-result lookup, and sandbox payment authorization for frictionless final states.

It does not type into Hosted Fields iframes or complete ACS challenges. Those parts require a browser, so test them with:

```bash
./run.sh dev node
```

## Environment

Each backend reads its own `.env` file.

```bash
GP_APP_ID=
GP_APP_KEY=
GP_API_ENVIRONMENT=sandbox

GP_ACCOUNT_NAME=transaction_processing
GP_ACCOUNT_ID=
GP_TOKENIZATION_ACCOUNT_NAME=

GP_PARTNER_MERCHANT_ID=

METHOD_NOTIFICATION_URL=
CHALLENGE_NOTIFICATION_URL=
FRONTEND_ORIGIN=http://localhost:8000
```

Notes:

- `GP_ACCOUNT_NAME` must match the developer portal exactly.
- `GP_ACCOUNT_ID` is the `TRA_...` transaction-processing account ID. Use it when available.
- `GP_TOKENIZATION_ACCOUNT_NAME` is optional. Leave it blank to use the tokenization account returned by the SDK token response.
- `GP_PARTNER_MERCHANT_ID` is only for partner-mode merchant routing. Most app credentials should leave it blank.
- `CHALLENGE_NOTIFICATION_URL` must be HTTPS for 3DS authentication requests.
- `METHOD_NOTIFICATION_URL` should also be HTTPS for full browser method callbacks.
- `FRONTEND_ORIGIN` is used by the 3DS callback pages when they post the result back to the frontend.

For local CLI smoke tests, a generic HTTPS webhook is enough for `CHALLENGE_NOTIFICATION_URL`. For browser challenge testing, expose the backend over HTTPS and point these URLs at:

```text
/3ds-method-notification
/3ds-challenge-notification
```

## API

All backends expose the same routes:

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/health` | Health check |
| GET | `/api/tokenization-config` | Short-lived Hosted Fields token config |
| POST | `/api/check-enrollment` | 3DS enrollment check |
| POST | `/api/initiate-auth` | 3DS authentication request |
| POST | `/api/get-auth-result` | Authentication result lookup |
| POST | `/api/authorize-payment` | Payment authorization |

Browser callback routes:

| Method | Path |
| --- | --- |
| GET/POST | `/3ds-method-notification` |
| GET/POST | `/3ds-challenge-notification` |

## Test Cards

Use expiry `12/2026` and CVV `123`.

| Card number | Expected result | ECI |
| --- | --- | --- |
| `4263970000005262` | Frictionless success | `05` |
| `5425230000004415` | Frictionless success | `02` |
| `4012001037141112` | Challenge required | |
| `5114610000004778` | Challenge or issuer decline path | |
| `4012001036853337` | Not enrolled / auth failed path | `07` |
| `4012001036273338` | Not enrolled / unavailable path | `07` |

## SDKs

| Backend | SDK package |
| --- | --- |
| Node.js | `globalpayments-api` |
| PHP | `globalpayments/php-sdk` |
| .NET | `GlobalPayments.Api` |
| Java | `com.globalpayments:globalpayments-sdk` |

Hosted Fields uses a short-lived token with `PMT_POST_Create_Single`. Each backend creates that token through its SDK.
