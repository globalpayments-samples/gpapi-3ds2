# GP-API 3DS2 Sandbox

A 3DS2 testing platform for the Global Payments Unified Commerce Platform (UCP). Four backend implementations (Node.js, PHP, .NET, Java) expose the same API, so you can swap runtimes without touching the frontend.

The default browser flow uses Global Payments Hosted Fields for single-use tokenization, then sends the payment method reference through the GP-API 3DS2 flow. A diagnostic raw-card API mode remains available for sandbox troubleshooting only.

---

## Running with Docker

```bash
cp nodejs/.env.example nodejs/.env   # then fill in credentials
cp php/.env.example php/.env
cp dotnet/.env.example dotnet/.env
cp java/.env.example java/.env

docker compose up --build
```

| Service   | URL                    |
|-----------|------------------------|
| Frontend  | http://localhost:8000  |
| Node.js   | http://localhost:8001  |
| PHP       | http://localhost:8003  |
| Java      | http://localhost:8004  |
| .NET      | http://localhost:8006  |

Open the frontend, pick a backend from the dropdown, and run through the 3DS2 flow.

---

## Running Natively

Use the root helper script for local development and verification:

```bash
./run.sh check             # syntax/build checks
./run.sh dev node          # frontend + Node backend for browser testing
./run.sh smoke node        # starts Node, runs real GP-API smoke test, stops Node
./run.sh smoke all         # runs the smoke test across all four backends
```

`./run.sh dev <backend>` starts the frontend at `http://localhost:8000` and the selected backend. Open the frontend, choose the matching backend in the dropdown, and run through the Hosted Fields flow.

Each backend can also run without the helper script. See the README in each subfolder for the specific commands.

```
nodejs/   Node 18+, Express
php/      PHP 8.3+, built-in CLI server
dotnet/   .NET 9, minimal API
java/     Java 21, Maven + Tomcat (Cargo)
```

---

## Environment Variables

Each backend needs its own `.env`. Copy from `.env.example` and fill in:

```
GP_APP_ID=
GP_APP_KEY=
GP_MERCHANT_ID=
GP_ACCOUNT_NAME=transaction_processing
GP_ACCOUNT_ID=
GP_API_ENVIRONMENT=sandbox
GP_TOKENIZATION_ACCOUNT_NAME=
```

`GP_ACCOUNT_NAME` is case-sensitive — use the exact value shown in your developer portal.
`GP_TOKENIZATION_ACCOUNT_NAME` is optional. Leave it blank to use the tokenization account returned in the restricted token scope.

---

## API Endpoints

All four backends expose the same six routes:

| Method | Path                    | Description                        |
|--------|-------------------------|------------------------------------|
| GET    | `/api/health`           | Returns backend name and status    |
| GET    | `/api/tokenization-config` | Returns a restricted Hosted Fields token |
| POST   | `/api/check-enrollment` | Step 1 — card enrollment check     |
| POST   | `/api/initiate-auth`    | Step 2 — 3DS method + auth request |
| POST   | `/api/get-auth-result`  | Step 3 — poll authentication result|
| POST   | `/api/authorize-payment`| Step 4 — authorize the payment     |

---

## Test Cards

All cards use expiry `12/2026` and CVV `123`.

| Card number      | Expected outcome     | ECI |
|------------------|----------------------|-----|
| 4263970000005262 | Frictionless success | 05  |
| 5425230000004415 | Frictionless success | 02  |
| 4012001037141112 | Challenge required   | —   |
| 5114610000004778 | Challenge required   | —   |
| 4012001036853337 | Auth failed          | 07  |
| 4012001036273338 | Unavailable          | 07  |

---

## Token Authentication

All backends generate Bearer tokens the same way:

```
nonce  = ISO-8601 datetime string
secret = hex(SHA512(nonce + APP_KEY))   ← no separator, no merchant_id
POST /ucp/accesstoken { app_id, nonce, secret, grant_type: "client_credentials" }
```

Tokens are cached and refreshed when under 60 seconds to expiry.

Hosted Fields uses a separate short-lived restricted token with `PMT_POST_Create_Single` permission. That token is intentionally not cached long-term.

---

## Running the CLI Smoke Test

```bash
./run.sh smoke node        # preferred
./test-all-cards.sh 3001 nodejs   # lower-level script if a backend is already running
```

Runs all 6 test cards through all 4 steps and reports pass/fail per card.

---

## Authentication Result Flow

- Enrollment check creates the authentication resource with `POST /authentications`.
- Initiation must continue the same resource with `POST /authentications/{AUT_ID}/initiate`.
- Result polling must use `GET /authentications/{AUT_ID}/result`.
- Keep the `AUT_` prefix for resource URLs and transaction authorization. The bare `three_ds.server_trans_ref` is not a replacement for the authentication resource ID.
