# Node.js Backend

Express server written as ES modules, using Node 18's built-in `fetch`. No GP SDK — all GP-API calls go out as plain HTTP requests.

Runs on port **3001** (Docker host port **8001**).

---

## Files

```
auth.js      Token generation and caching (OAuth2 Bearer)
server.js    Express routes for all 5 API endpoints
package.json express + dotenv only
Dockerfile
.env.example
```

---

## Setup

```bash
cp .env.example .env
# fill in GP_APP_ID, GP_APP_KEY, GP_MERCHANT_ID, GP_ACCOUNT_NAME, GP_ACCOUNT_ID

npm install
node server.js
```

Server starts at `http://localhost:3001`.

---

## Environment Variables

```
GP_APP_ID=
GP_APP_KEY=
GP_MERCHANT_ID=
GP_ACCOUNT_NAME=transaction_processing
GP_ACCOUNT_ID=
PORT=3001
```

---

## Endpoints

```
GET  /api/health
POST /api/check-enrollment
POST /api/initiate-auth
POST /api/get-auth-result
POST /api/authorize-payment
```

---

## Notes

- Token logic is in `auth.js`: ISO-8601 nonce, `SHA512(nonce + appKey)` with no separator, no `merchant_id` in the token request body.
- Tokens are cached in memory and refreshed when under 60 seconds to expiry.
- `npm ci` will fail if there's no lockfile — use `npm install` instead.
