# .NET Backend

ASP.NET Core minimal API for the shared 3DS2 sample. Server-side Global Payments calls use `GlobalPayments.Api`; the browser uses Hosted Fields for card entry.

Default local port: `8006`. The Docker container listens on `8080` and maps to `8006`.

## Run

```bash
cp .env.example .env
dotnet restore
GP_SAMPLE_PORT=8006 dotnet run
```

Or from the repo root:

```bash
./run.sh dev dotnet
./run.sh smoke dotnet
```

## Environment

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

PORT=8080
GP_SAMPLE_PORT=8006
```

`CHALLENGE_NOTIFICATION_URL` must be HTTPS for 3DS auth calls. For browser challenge testing, expose this backend over HTTPS and point the notification URLs at `/3ds-method-notification` and `/3ds-challenge-notification`.

## Routes

```text
GET  /api/health
GET  /api/tokenization-config
POST /api/check-enrollment
POST /api/initiate-auth
POST /api/get-auth-result
POST /api/authorize-payment

GET/POST /3ds-method-notification
GET/POST /3ds-challenge-notification
```

## Files

```text
Program.cs       Routes, SDK setup, and mapping helpers
dotnet.csproj    Dependencies
appsettings.json ASP.NET config
Dockerfile
.env.example
```

`GP_SAMPLE_PORT` is used for local runs. Docker uses `PORT=8080`.
