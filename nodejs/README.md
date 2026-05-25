# Node.js Backend

Express backend for the shared 3DS2 sample. Server-side Global Payments calls use `globalpayments-api`; the browser uses Hosted Fields for card entry.

Default port: `3001`.

## Run

```bash
cp .env.example .env
npm install
node server.js
```

Or from the repo root:

```bash
./run.sh dev node
./run.sh smoke node
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

PORT=3001
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
gp-sdk.js      SDK setup and mapping helpers
server.js      Express routes
package.json   Dependencies and start script
Dockerfile
.env.example
```

`gp-sdk.js` is the main place to look for SDK configuration.
