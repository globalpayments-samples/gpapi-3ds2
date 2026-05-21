# Java Backend

Jakarta EE servlet deployed to Tomcat via the Maven Cargo plugin. Uses Java's built-in `HttpClient` for direct GP-API calls, while the browser uses Hosted Fields for single-use tokenization.

Runs on port **8080** (Docker host port **8004**). Requires **Java 21**.

---

## Files

```
src/main/java/com/globalpayments/example/GpApi3dsServlet.java   All routes and GP-API logic
pom.xml   Jackson + dotenv-java + servlet-api + Cargo/Tomcat
Dockerfile
.env.example
```

---

## Setup

```bash
cp .env.example .env
# fill in GP_APP_ID, GP_APP_KEY, GP_MERCHANT_ID, GP_ACCOUNT_NAME, GP_ACCOUNT_ID

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
mvn clean package cargo:run
```

Server starts at `http://localhost:8080`.

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

- GP-API returns gzip-encoded responses. The servlet detects the gzip magic bytes (`0x1F 0x8B`) and decompresses with `GZIPInputStream` before parsing JSON.
- Token is stored in a `volatile` field and regenerated thread-safely when under 60 seconds to expiry.
- Always build with `mvn clean package` — stale `.class` files from a different JDK version will cause `UnsupportedClassVersionError` at runtime.
- Make sure `JAVA_HOME` points to Java 21. The system default may be a newer version that produces class files incompatible with the Tomcat runtime.
