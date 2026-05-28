# Java Backend

Jakarta servlet backend for the shared 3DS2 sample. Server-side Global Payments calls use `com.globalpayments:globalpayments-sdk`; the browser uses Hosted Fields for card entry.

Default local port: `8004`. The Docker container listens on `8080` and maps to `8004`.

Requires Java 21.

## Run

```bash
cp .env.example .env
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
mvn clean package cargo:run
```

Or from the repo root:

```bash
./run.sh dev java
./run.sh smoke java
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
src/main/java/com/globalpayments/example/GpApi3dsServlet.java
pom.xml
Dockerfile
.env.example
```

Use `mvn clean package` after changing JDK versions. Stale class files can cause `UnsupportedClassVersionError` when Tomcat starts.
