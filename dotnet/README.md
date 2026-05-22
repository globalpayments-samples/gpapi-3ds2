# .NET Backend

ASP.NET Core minimal API targeting .NET 9. Uses `HttpClient` with automatic gzip decompression. Token is held in memory. GP-API calls are direct HTTP, while the browser uses Hosted Fields for single-use tokenization.

Runs on port **8006** with the root helper script. The default/internal container port is `8080`.

---

## Files

```
Program.cs       All routes and GP-API HTTP logic
dotnet.csproj    DotEnv.Net only (no GP SDK)
appsettings.json Standard ASP.NET config (logging, allowed hosts)
Dockerfile
.env.example
```

---

## Setup

```bash
cp .env.example .env
# fill in GP_APP_ID, GP_APP_KEY, GP_MERCHANT_ID, GP_ACCOUNT_NAME, GP_ACCOUNT_ID

dotnet restore
GP_SAMPLE_PORT=8006 dotnet run
```

Server starts at `http://localhost:8006`.

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
PORT=8080
GP_SAMPLE_PORT=8006
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

- `HttpClient` is configured with `AutomaticDecompression = All` — GP-API returns gzip-encoded responses.
- Token is cached in a static variable and regenerated when under 60 seconds to expiry.
- `GP_SAMPLE_PORT` overrides `PORT` for local helper-script runs. Docker uses internal port `8080` and maps it to host port `8006`.
