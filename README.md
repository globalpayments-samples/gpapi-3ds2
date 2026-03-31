# GP-API 3DS2 Sandbox

A 3DS2 testing platform for the Global Payments Unified Commerce Platform (UCP). Four backend implementations (Node.js, PHP, .NET, Java) expose the same API, so you can swap runtimes without touching the frontend.

No GP SDK is used — all backends talk directly to `https://apis.sandbox.globalpay.com/ucp` over plain HTTP.

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

Each backend can run without Docker. See the README in each subfolder for the specific commands.

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
```

`GP_ACCOUNT_NAME` is case-sensitive — use the exact value shown in your developer portal.

---

## API Endpoints

All four backends expose the same five routes:

| Method | Path                    | Description                        |
|--------|-------------------------|------------------------------------|
| GET    | `/api/health`           | Returns backend name and status    |
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

---

## Running the CLI Smoke Test

```bash
./test-all-cards.sh 3001 nodejs   # or 8003/php, 8006/dotnet, 8004/java
```

Runs all 6 test cards through all 4 steps and reports pass/fail per card.

---

## Known Sandbox Limitations

- `GET /authentications/{id}` returns error 40212 — the sandbox credentials don't have read permission on that resource. This needs to be enabled by the GP-API team.
- `initiate-auth` returns `AVAILABLE` instead of `SUCCESS_AUTHENTICATED` for frictionless cards — the sandbox 3DS Server doesn't recognise `developer.globalpayments.com` as a trusted method notification URL. A public `three_ds_method_return_url` on GP-API infrastructure is needed to get true frictionless success.

These are sandbox account limitations, not bugs in the code.
