package com.globalpayments.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.zip.GZIPInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * GP-API 3DS2 Backend — Java Servlet
 *
 * Implements all 5 API endpoints using direct HTTP calls to GP-API UCP.
 * Token caching with thread-safe refresh.
 */
@WebServlet(urlPatterns = {
    "/api/health",
    "/api/check-enrollment",
    "/api/initiate-auth",
    "/api/get-auth-result",
    "/api/authorize-payment"
})
public class GpApi3dsServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final String BASE_URL   = "https://apis.sandbox.globalpay.com/ucp";
    private static final String GP_VERSION = "2021-03-22";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient   HTTP   = HttpClient.newHttpClient();

    // Token cache
    private static final ReentrantLock TOKEN_LOCK = new ReentrantLock();
    private static volatile String cachedToken    = null;
    private static volatile long   tokenExpiresAt = 0; // epoch ms

    private Dotenv dotenv;

    @Override
    public void init() throws ServletException {
        dotenv = Dotenv.configure().ignoreIfMissing().load();
    }

    // ── CORS ─────────────────────────────────────────────────────────────────

    private static void addCors(HttpServletResponse res) {
        res.setHeader("Access-Control-Allow-Origin",  "*");
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        addCors(res);
        res.setStatus(204);
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        addCors(res);
        res.setContentType("application/json");

        if ("/api/health".equals(req.getServletPath())) {
            res.getWriter().write("{\"status\":\"ok\",\"backend\":\"java\",\"version\":\"1.0.0\"}");
        } else {
            res.setStatus(404);
            res.getWriter().write("{\"error\":\"Not found\"}");
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {
        addCors(res);
        res.setContentType("application/json");

        String body = req.getReader().lines().collect(Collectors.joining());
        JsonNode input = MAPPER.readTree(body.isEmpty() ? "{}" : body);

        try {
            switch (req.getServletPath()) {
                case "/api/check-enrollment"  -> handleCheckEnrollment(input, res);
                case "/api/initiate-auth"     -> handleInitiateAuth(input, res);
                case "/api/get-auth-result"   -> handleGetAuthResult(input, res);
                case "/api/authorize-payment" -> handleAuthorizePayment(input, res);
                default -> { res.setStatus(404); res.getWriter().write("{\"error\":\"Not found\"}"); }
            }
        } catch (Exception e) {
            writeError(res, e);
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private void handleCheckEnrollment(JsonNode in, HttpServletResponse res) throws Exception {
        String cardNumber = text(in, "card_number");
        String expMonth   = text(in, "exp_month");
        String expYear    = text(in, "exp_year");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("account_name", env("GP_ACCOUNT_NAME", "transaction_processing"));
        String accountId = env("GP_ACCOUNT_ID", "");
        if (!accountId.isEmpty()) payload.put("account_id", accountId);
        String merchantId = env("GP_MERCHANT_ID", "");
        if (!merchantId.isEmpty()) payload.put("merchant_id", merchantId);
        payload.put("channel",  "CNP");
        payload.put("country",  "GB");
        payload.put("amount",   "1000");
        payload.put("currency", "GBP");
        payload.put("reference", UUID.randomUUID().toString());
        payload.putObject("payment_method")
               .put("entry_mode", "ECOM")
               .putObject("card")
               .put("number",       cardNumber)
               .put("expiry_month", expMonth)
               .put("expiry_year",  twoDigitYear(expYear));
        payload.putObject("three_ds")
               .put("source",          "BROWSER")
               .put("preference",      "NO_PREFERENCE")
               .put("message_version", "2.2.0");
        ObjectNode notif = payload.putObject("notifications");
        notif.put("challenge_return_url",        env("CHALLENGE_NOTIFICATION_URL", ""));
        notif.put("three_ds_method_return_url",  env("METHOD_NOTIFICATION_URL",    ""));

        JsonNode raw = gpPost("/authentications", payload);

        String methodUrl  = null;
        String methodData = null;
        JsonNode tds3 = raw.path("three_ds");
        if (!tds3.isMissingNode() && !tds3.path("method_url").isMissingNode()
                && !tds3.path("method_url").isNull()) {
            methodUrl = tds3.path("method_url").asText();
            String mJson = MAPPER.writeValueAsString(MAPPER.createObjectNode()
                .put("threeDSServerTransID",  raw.path("id").asText())
                .put("methodNotificationURL", env("METHOD_NOTIFICATION_URL", "")));
            methodData = Base64.getEncoder().encodeToString(mJson.getBytes(StandardCharsets.UTF_8));
        }

        ObjectNode data = MAPPER.createObjectNode();
        data.put("server_trans_id",  raw.path("id").asText());
        data.put("server_trans_ref", tds3.path("server_trans_ref").asText(null));
        data.put("enrolled",         tds3.path("enrolled_status").asText(null));
        data.put("message_version",  tds3.path("message_version").asText(null));
        data.put("method_url",       methodUrl);
        data.put("method_data",      methodData);

        writeSuccess(res, data, raw);
    }

    private void handleInitiateAuth(JsonNode in, HttpServletResponse res) throws Exception {
        String serverTransIdRaw    = textOr(in, "server_trans_id", "");
        String serverTransId       = serverTransIdRaw.startsWith("AUT_") ? serverTransIdRaw.substring(4) : serverTransIdRaw;
        String messageVersion      = textOr(in, "message_version", "2.1.0");
        String methodUrlCompletion = textOr(in, "method_url_completion", "UNAVAILABLE");
        String cardNumber          = text(in, "card_number");
        String expMonth            = text(in, "exp_month");
        String expYear             = text(in, "exp_year");
        String cardholderName      = textOr(in, "cardholder_name", "Test User");
        JsonNode order             = in.path("order");
        String amount              = order.path("amount").asText("10.00");
        String currency            = order.path("currency").asText("GBP");
        JsonNode bd                = in.path("browser_data");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("account_name", env("GP_ACCOUNT_NAME", "transaction_processing"));
        String accountIdI = env("GP_ACCOUNT_ID", "");
        if (!accountIdI.isEmpty()) payload.put("account_id", accountIdI);
        String merchantIdI = env("GP_MERCHANT_ID", "");
        if (!merchantIdI.isEmpty()) payload.put("merchant_id", merchantIdI);
        payload.put("channel",   "CNP");
        payload.put("country",   "GB");
        payload.put("amount",    toMinorUnits(amount));
        payload.put("currency",  currency);
        payload.put("reference", UUID.randomUUID().toString());
        payload.putObject("payment_method")
               .put("entry_mode", "ECOM")
               .putObject("card")
               .put("number",       cardNumber)
               .put("expiry_month", expMonth)
               .put("expiry_year",  twoDigitYear(expYear))
               .put("full_name",    cardholderName);
        payload.putObject("three_ds")
               .put("source",                "BROWSER")
               .put("preference",            "NO_PREFERENCE")
               .put("message_version",       messageVersion)
               .put("server_trans_ref",      serverTransId)
               .put("method_url_completion", methodUrlCompletion);
        payload.putObject("order")
               .put("amount",           toMinorUnits(amount))
               .put("currency",         currency)
               .put("reference",        UUID.randomUUID().toString())
               .put("address_indicator", false)
               .put("date_time_created", Instant.now().toString());
        payload.putObject("payer")
               .put("email", "test@example.com")
               .putObject("billing_address")
               .put("line1",       "1 Test Street")
               .put("city",        "London")
               .put("postal_code", "SW1A 1AA")
               .put("country",     "826");
        payload.putObject("browser_data")
               .put("accept_header",         bdField(bd, "accept_header",         "text/html,application/xhtml+xml"))
               .put("color_depth",           bdField(bd, "color_depth",           "24"))
               .put("ip",                    bdField(bd, "ip",                    "123.123.123.123"))
               .put("java_enabled",          bdField(bd, "java_enabled",          "false"))
               .put("javascript_enabled",    bdField(bd, "javascript_enabled",    "true"))
               .put("language",              bdField(bd, "language",              "en-GB"))
               .put("screen_height",         bdField(bd, "screen_height",         "1080"))
               .put("screen_width",          bdField(bd, "screen_width",          "1920"))
               .put("challenge_window_size", bdField(bd, "challenge_window_size", "FULL_SCREEN"))
               .put("timezone",              bdField(bd, "timezone",              "0"))
               .put("user_agent",            bdField(bd, "user_agent",            "Mozilla/5.0"));
        payload.putObject("notifications")
               .put("challenge_return_url",       env("CHALLENGE_NOTIFICATION_URL", ""))
               .put("three_ds_method_return_url", env("METHOD_NOTIFICATION_URL",    ""));

        JsonNode raw = gpPost("/authentications", payload);
        JsonNode tds = raw.path("three_ds");

        ObjectNode data = MAPPER.createObjectNode();
        data.put("server_trans_id",      raw.path("id").asText());
        data.put("status",               raw.path("status").asText(null));
        data.put("acs_reference_number", tds.path("acs_reference_number").asText(null));
        data.put("acs_trans_id",         tds.path("acs_trans_id").asText(null));
        data.put("acs_signed_content",   tds.path("acs_signed_content").asText(null));
        String challengeUrl = tds.has("acs_challenge_url") ? tds.path("acs_challenge_url").asText(null)
                            : tds.path("challenge_value").asText(null);
        data.put("acs_challenge_url", challengeUrl);

        writeSuccess(res, data, raw);
    }

    private void handleGetAuthResult(JsonNode in, HttpServletResponse res) throws Exception {
        String serverTransIdRaw = text(in, "server_trans_id");
        String serverTransId    = serverTransIdRaw != null ? serverTransIdRaw.replaceFirst("^AUT_", "") : "";
        if (serverTransId.isEmpty()) {
            res.setStatus(400);
            res.getWriter().write("{\"success\":false,\"error\":\"server_trans_id is required\"}");
            return;
        }

        JsonNode raw = gpGet("/authentications/" + serverTransId);
        JsonNode tds = raw.path("three_ds");

        ObjectNode data = MAPPER.createObjectNode();
        data.put("status",               raw.path("status").asText(null));
        data.put("eci",                  tds.path("eci").asText(null));
        data.put("authentication_value", tds.path("authentication_value").asText(null));
        data.put("ds_trans_ref",         tds.path("ds_trans_ref").asText(null));
        data.put("message_version",      tds.path("message_version").asText(null));
        data.put("server_trans_ref",     tds.has("server_trans_ref") ? tds.path("server_trans_ref").asText() : raw.path("id").asText());

        writeSuccess(res, data, raw);
    }

    private void handleAuthorizePayment(JsonNode in, HttpServletResponse res) throws Exception {
        String cardNumber = text(in, "card_number");
        String expMonth   = text(in, "exp_month");
        String expYear    = text(in, "exp_year");
        String cvn        = textOr(in, "cvn", "");
        String amount     = textOr(in, "amount",   "10.00");
        String currency   = textOr(in, "currency", "GBP");
        JsonNode tds3     = in.path("three_ds");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("account_name", env("GP_ACCOUNT_NAME", "transaction_processing"));
        payload.put("channel",   "CNP");
        payload.put("type",      "SALE");
        payload.put("amount",    toMinorUnits(amount));
        payload.put("currency",  currency);
        payload.put("reference", UUID.randomUUID().toString());
        payload.put("country",   "GB");
        payload.putObject("payment_method")
               .put("entry_mode", "ECOM")
               .putObject("card")
               .put("number",       cardNumber)
               .put("expiry_month", expMonth)
               .put("expiry_year",  twoDigitYear(expYear))
               .put("cvv",          cvn);
        payload.putObject("three_ds")
               .put("source",               "BROWSER")
               .put("authentication_value", tds3.path("authentication_value").asText(null))
               .put("server_trans_ref",     tds3.path("server_trans_ref").asText(null))
               .put("ds_trans_ref",         tds3.path("ds_trans_ref").asText(null))
               .put("eci",                  tds3.path("eci").asText(null))
               .put("message_version",      tds3.path("message_version").asText("2.2.0"));

        JsonNode raw = gpPost("/transactions", payload);

        ObjectNode data = MAPPER.createObjectNode();
        data.put("transaction_id", raw.path("id").asText());
        data.put("status",         raw.path("status").asText(null));
        data.put("result_code",    raw.path("action").path("result_code").asText(null));
        data.put("amount",         raw.path("amount").asText(null));
        data.put("currency",       raw.path("currency").asText(null));

        writeSuccess(res, data, raw);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private JsonNode gpPost(String path, ObjectNode body) throws Exception {
        return gpRequest("POST", path, body);
    }

    private JsonNode gpGet(String path) throws Exception {
        return gpRequest("GET", path, null);
    }

    private JsonNode gpRequest(String method, String path, ObjectNode body) throws Exception {
        String token = getAccessToken();
        HttpRequest.Builder rb = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + path))
            .header("Content-Type",  "application/json")
            .header("X-GP-Version",  GP_VERSION)
            .header("Authorization", "Bearer " + token);

        if ("GET".equals(method)) {
            rb.GET();
        } else {
            String payload = body != null ? MAPPER.writeValueAsString(body) : "{}";
            rb.method(method, HttpRequest.BodyPublishers.ofString(payload));
        }

        HttpResponse<byte[]> resp = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        String bodyStr = decodeBody(resp);
        JsonNode result = MAPPER.readTree(bodyStr.isEmpty() ? "{}" : bodyStr);

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            RuntimeException ex = new RuntimeException(
                result.path("error").path("message").asText("GP-API error " + resp.statusCode()));
            ex.addSuppressed(new RuntimeException("STATUS:" + resp.statusCode()));
            ex.addSuppressed(new RuntimeException("RAW:" + bodyStr));
            throw ex;
        }
        return result;
    }

    // ── Token management ─────────────────────────────────────────────────────

    private String getAccessToken() throws Exception {
        if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
            return cachedToken;
        }
        TOKEN_LOCK.lock();
        try {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAt) {
                return cachedToken;
            }
            return generateToken();
        } finally {
            TOKEN_LOCK.unlock();
        }
    }

    private String generateToken() throws Exception {
        String appId  = env("GP_APP_ID",  "");
        String appKey = env("GP_APP_KEY", "");
        if (appId.isEmpty() || appKey.isEmpty()) throw new Exception("GP_APP_ID and GP_APP_KEY must be set");

        String nonce  = java.time.Instant.now().toString(); // ISO-8601 e.g. 2026-03-24T14:37:06.444Z
        String secret = sha512Hex(nonce + appKey);

        ObjectNode body = MAPPER.createObjectNode();
        body.put("app_id",     appId);
        body.put("nonce",      nonce);
        body.put("secret",     secret);
        body.put("grant_type", "client_credentials");

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/accesstoken"))
            .header("Content-Type", "application/json")
            .header("X-GP-Version", GP_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();

        HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        String tokenBody = decodeBody(resp);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new Exception("Token generation failed (" + resp.statusCode() + "): " + tokenBody);
        }

        JsonNode result  = MAPPER.readTree(tokenBody);
        cachedToken      = result.get("token").asText();
        int expiresIn    = result.path("seconds_to_expire").asInt(3599);
        tokenExpiresAt   = System.currentTimeMillis() + (expiresIn - 60) * 1000L;
        return cachedToken;
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    private static String decodeBody(HttpResponse<byte[]> resp) throws IOException {
        byte[] bytes = resp.body();
        if (bytes == null || bytes.length == 0) return "";
        String enc = resp.headers().firstValue("content-encoding").orElse("").toLowerCase();
        if (enc.contains("gzip") || (bytes.length > 1 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B)) {
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── Response writers ─────────────────────────────────────────────────────

    private static void writeSuccess(HttpServletResponse res, ObjectNode data, JsonNode raw) throws IOException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("success", true);
        out.set("data", data);
        out.set("raw",  raw);
        res.getWriter().write(MAPPER.writeValueAsString(out));
    }

    private static void writeError(HttpServletResponse res, Exception e) throws IOException {
        // Extract HTTP status from suppressed if present
        int status = 500;
        String rawBody = null;
        for (Throwable s : e.getSuppressed()) {
            if (s.getMessage().startsWith("STATUS:")) status = Integer.parseInt(s.getMessage().substring(7));
            if (s.getMessage().startsWith("RAW:"))    rawBody = s.getMessage().substring(4);
        }
        res.setStatus(status);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("success", false);
        out.put("error",   e.getMessage());
        if (rawBody != null) {
            try { out.set("raw", MAPPER.readTree(rawBody)); } catch (Exception ignored) {}
        }
        res.getWriter().write(MAPPER.writeValueAsString(out));
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String env(String key, String def) {
        String v = dotenv.get(key, null);
        return (v != null && !v.isEmpty()) ? v : def;
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode node, String key, String def) {
        String v = text(node, key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String bdField(JsonNode bd, String key, String def) {
        if (bd == null || bd.isMissingNode()) return def;
        JsonNode v = bd.path(key);
        return v.isMissingNode() || v.isNull() ? def : v.asText();
    }

    private static String toMinorUnits(String amount) {
        return String.valueOf(new BigDecimal(amount)
            .multiply(BigDecimal.valueOf(100))
            .setScale(0, RoundingMode.HALF_UP)
            .intValueExact());
    }

    private static String twoDigitYear(String year) {
        return year != null && year.length() > 2 ? year.substring(year.length() - 2) : year;
    }

    private static String sha512Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
