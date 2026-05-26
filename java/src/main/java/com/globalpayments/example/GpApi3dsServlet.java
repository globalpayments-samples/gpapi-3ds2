package com.globalpayments.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.global.api.ServicesContainer;
import com.global.api.entities.Address;
import com.global.api.entities.BrowserData;
import com.global.api.entities.ThreeDSecure;
import com.global.api.entities.Transaction;
import com.global.api.entities.enums.AddressType;
import com.global.api.entities.enums.AuthenticationSource;
import com.global.api.entities.enums.Channel;
import com.global.api.entities.enums.ChallengeWindowSize;
import com.global.api.entities.enums.ColorDepth;
import com.global.api.entities.enums.Environment;
import com.global.api.entities.enums.IntervalToExpire;
import com.global.api.entities.enums.MethodUrlCompletion;
import com.global.api.entities.gpApi.entities.AccessTokenInfo;
import com.global.api.paymentMethods.CreditCardData;
import com.global.api.serviceConfigs.GpApiConfig;
import com.global.api.services.GpApiService;
import com.global.api.services.Secure3dService;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

@WebServlet(urlPatterns = {
    "/api/health",
    "/api/tokenization-config",
    "/api/check-enrollment",
    "/api/initiate-auth",
    "/api/get-auth-result",
    "/api/authorize-payment",
    "/3ds-method-notification",
    "/3ds-challenge-notification"
})
public class GpApi3dsServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final String CONFIG_NAME = "gpapi-3ds-sample";
    private static final String GP_VERSION = "2021-03-22";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private Dotenv dotenv;
    private boolean configured = false;

    @Override
    public void init() throws ServletException {
        dotenv = Dotenv.configure().ignoreIfMissing().load();
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse res) {
        addCors(res);
        res.setStatus(204);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        addCors(res);
        res.setContentType("application/json");

        try {
            switch (req.getServletPath()) {
                case "/api/health" -> res.getWriter().write("{\"status\":\"ok\",\"backend\":\"java\",\"version\":\"1.0.0\",\"sdk\":\"globalpayments-sdk\"}");
                case "/api/tokenization-config" -> writeTokenizationConfig(res);
                case "/3ds-method-notification" -> writeNotificationPage(res, "handleMethodNotification", req.getParameter("threeDSMethodData"));
                case "/3ds-challenge-notification" -> writeNotificationPage(res, "handleChallengeNotification",
                    req.getParameter("cres") != null ? req.getParameter("cres") : req.getParameter("CRes"));
                default -> {
                    res.setStatus(404);
                    res.getWriter().write("{\"error\":\"Not found\"}");
                }
            }
        } catch (Exception e) {
            writeError(res, e);
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        addCors(res);

        if ("/3ds-method-notification".equals(req.getServletPath())) {
            writeNotificationPage(res, "handleMethodNotification", req.getParameter("threeDSMethodData"));
            return;
        }
        if ("/3ds-challenge-notification".equals(req.getServletPath())) {
            writeNotificationPage(res, "handleChallengeNotification",
                req.getParameter("cres") != null ? req.getParameter("cres") : req.getParameter("CRes"));
            return;
        }

        res.setContentType("application/json");

        String body = req.getReader().lines().collect(Collectors.joining());
        JsonNode input = MAPPER.readTree(body.isEmpty() ? "{}" : body);

        try {
            switch (req.getServletPath()) {
                case "/api/check-enrollment" -> handleCheckEnrollment(input, res);
                case "/api/initiate-auth" -> handleInitiateAuth(input, res);
                case "/api/get-auth-result" -> handleGetAuthResult(input, res);
                case "/api/authorize-payment" -> handleAuthorizePayment(input, res);
                default -> {
                    res.setStatus(404);
                    res.getWriter().write("{\"error\":\"Not found\"}");
                }
            }
        } catch (Exception e) {
            writeError(res, e);
        }
    }

    private void writeTokenizationConfig(HttpServletResponse res) throws Exception {
        AccessTokenInfo token = GpApiService.generateTransactionKey(sdkConfig(true));
        ObjectNode data = MAPPER.createObjectNode();
        data.put("env", env("GP_API_ENVIRONMENT", "sandbox"));
        data.put("accessToken", token.getAccessToken());
        data.put("accountName", env("GP_TOKENIZATION_ACCOUNT_NAME",
            token.getTokenizationAccountName() != null ? token.getTokenizationAccountName() : env("GP_ACCOUNT_NAME", "transaction_processing")));
        data.put("merchantId", token.getMerchantId());
        data.put("apiVersion", GP_VERSION);
        writeJson(res, MAPPER.createObjectNode().put("success", true).set("data", data));
    }

    private void writeNotificationPage(HttpServletResponse res, String handler, String data) throws IOException {
        String nonce = notificationNonce();
        res.setContentType("text/html");
        res.setHeader("Content-Security-Policy", "default-src 'none'; script-src 'nonce-" + nonce + "' https://cdn.jsdelivr.net; base-uri 'none'; frame-ancestors 'self'");
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        String origin = env("FRONTEND_ORIGIN", "http://localhost:8000");
        String encodedData = MAPPER.writeValueAsString(encodeNotificationPayload(data));
        String encodedOrigin = MAPPER.writeValueAsString(origin);
        res.getWriter().write("""
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>3DS Notification</title>
              <script src="https://cdn.jsdelivr.net/npm/globalpayments-3ds@1.8.7/dist/globalpayments-3ds.min.js"></script>
            </head>
            <body>
            """);
        res.getWriter().write("<script nonce=\"" + nonce + "\">");
        res.getWriter().write("const notificationData = atob(" + encodedData + ");");
        res.getWriter().write("window.GlobalPayments?.ThreeDSecure?." + handler + "(notificationData, " + encodedOrigin + ");");
        res.getWriter().write("""
            </script>
            </body>
            </html>
            """);
    }

    private String encodeNotificationPayload(String data) {
        String value = data == null ? "" : data;
        if (value.length() > 12000 || !value.matches("^[A-Za-z0-9+/=_-]*$")) {
            return "";
        }
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String notificationNonce() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void handleCheckEnrollment(JsonNode input, HttpServletResponse res) throws Exception {
        ensureConfigured();
        ThreeDSecure secure = Secure3dService.checkEnrollment(paymentMethod(input))
            .withAmount(amountDecimal(input))
            .withCurrency(currency(input))
            .execute(CONFIG_NAME);
        writeSuccess(res, mapEnrollment(secure), secure);
    }

    private void handleInitiateAuth(JsonNode input, HttpServletResponse res) throws Exception {
        ensureConfigured();
        ThreeDSecure seed = new ThreeDSecure();
        seed.setServerTransactionId(text(input, "server_trans_id", ""));

        ThreeDSecure secure = Secure3dService.initiateAuthentication(paymentMethod(input), seed)
            .withAmount(amountDecimal(input))
            .withCurrency(currency(input))
            .withOrderCreateDate(org.joda.time.DateTime.now())
            .withAddress(shippingAddress(), AddressType.Shipping)
            .withAuthenticationSource(AuthenticationSource.Browser)
            .withBrowserData(browserData(input.path("browser_data")))
            .withMethodUrlCompletion(methodCompletion(text(input, "method_url_completion", "UNAVAILABLE")))
            .execute(CONFIG_NAME);
        writeSuccess(res, mapAuthentication(secure), secure);
    }

    private void handleGetAuthResult(JsonNode input, HttpServletResponse res) throws Exception {
        ensureConfigured();
        String serverTransId = text(input, "server_trans_id", "").trim();
        if (serverTransId.isEmpty()) {
            res.setStatus(400);
            res.getWriter().write("{\"success\":false,\"error\":\"server_trans_id is required\"}");
            return;
        }

        ThreeDSecure secure = Secure3dService.getAuthenticationData()
            .withServerTransactionId(serverTransId)
            .withAmount(amountDecimal(input))
            .execute(CONFIG_NAME);
        writeSuccess(res, mapAuthentication(secure), secure);
    }

    private void handleAuthorizePayment(JsonNode input, HttpServletResponse res) throws Exception {
        ensureConfigured();
        String authenticationId = text(input, "authentication_id",
            input.path("three_ds").path("authentication_id").asText(input.path("three_ds").path("server_trans_ref").asText(null)));
        ThreeDSecure secure = null;
        if (authenticationId != null && !authenticationId.isBlank()) {
            secure = Secure3dService.getAuthenticationData()
                .withServerTransactionId(authenticationId)
                .withAmount(amountDecimal(input))
                .execute(CONFIG_NAME);
        }

        CreditCardData card = paymentMethod(input);
        if (secure != null) {
            card.setThreeDSecure(secure);
        }
        Transaction transaction = card.charge(new BigDecimal(amount(input)))
            .withCurrency(currency(input))
            .execute(CONFIG_NAME);
        ObjectNode data = mapTransaction(transaction);
        writeSuccess(res, data, data);
    }

    private GpApiConfig sdkConfig(boolean tokenization) {
        GpApiConfig config = new GpApiConfig();
        config.setAppId(required("GP_APP_ID"));
        config.setAppKey(required("GP_APP_KEY"));
        config.setEnvironment("production".equalsIgnoreCase(env("GP_API_ENVIRONMENT", "sandbox")) ? Environment.PRODUCTION : Environment.TEST);
        config.setCountry(env("GP_COUNTRY", "GB"));
        config.setChannel(Channel.CardNotPresent);
        config.setMerchantId(env("GP_PARTNER_MERCHANT_ID", null));
        config.setMethodNotificationUrl(env("METHOD_NOTIFICATION_URL", null));
        config.setChallengeNotificationUrl(env("CHALLENGE_NOTIFICATION_URL", null));
        config.setMerchantContactUrl(env("GP_MERCHANT_CONTACT_URL", "https://developer.globalpay.com/"));
        config.setAccessTokenInfo(accessTokenInfo());
        if (tokenization) {
            config.setPermissions(new String[] {"PMT_POST_Create_Single"});
            config.setIntervalToExpire(IntervalToExpire.TEN_MINUTES);
        }
        return config;
    }

    private void ensureConfigured() throws Exception {
        if (configured) {
            return;
        }
        ServicesContainer.configureService(sdkConfig(false), CONFIG_NAME);
        configured = true;
    }

    private AccessTokenInfo accessTokenInfo() {
        return new AccessTokenInfo()
            .setMerchantId(env("GP_PARTNER_MERCHANT_ID", null))
            .setTransactionProcessingAccountName(env("GP_ACCOUNT_NAME", "transaction_processing"))
            .setTransactionProcessingAccountID(env("GP_ACCOUNT_ID", null))
            .setTokenizationAccountName(env("GP_TOKENIZATION_ACCOUNT_NAME", null));
    }

    private CreditCardData paymentMethod(JsonNode input) {
        CreditCardData card = new CreditCardData();
        String token = text(input, "payment_method_id", null);
        if (token != null && !token.isBlank()) {
            card.setToken(token);
        } else {
            card.setNumber(text(input, "card_number", ""));
            card.setExpMonth(parseInt(text(input, "exp_month", "0")));
            card.setExpYear(parseInt(text(input, "exp_year", "0")));
            card.setCvn(text(input, "cvn", ""));
        }
        card.setCardHolderName(text(input, "cardholder_name", "Test User"));
        return card;
    }

    private BrowserData browserData(JsonNode input) {
        return new BrowserData()
            .setAcceptHeader(text(input, "accept_header", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
            .setColorDepth(colorDepth(input.path("color_depth").asInt(24)))
            .setIpAddress(text(input, "ip", "127.0.0.1"))
            .setJavaEnabled(input.path("java_enabled").asBoolean(false))
            .setJavaScriptEnabled(input.path("javascript_enabled").asBoolean(true))
            .setLanguage(text(input, "language", "en-GB"))
            .setScreenHeight(input.path("screen_height").asInt(1080))
            .setScreenWidth(input.path("screen_width").asInt(1920))
            .setChallengeWindowSize(challengeWindow(text(input, "challenge_window_size", "FULL_SCREEN")))
            .setTimezone(text(input, "timezone", "0"))
            .setUserAgent(text(input, "user_agent", "Mozilla/5.0"));
    }

    private Address shippingAddress() {
        Address address = new Address();
        address.setStreetAddress1("1 Test Street");
        address.setCity("London");
        address.setPostalCode("SW1A 1AA");
        address.setCountryCode("826");
        return address;
    }

    private ObjectNode mapEnrollment(ThreeDSecure secure) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("server_trans_id", secure.getServerTransactionId());
        data.put("server_trans_ref", secure.getProviderServerTransRef() != null ? secure.getProviderServerTransRef() : secure.getServerTransactionId());
        data.put("enrolled", secure.getEnrolledStatus());
        data.put("message_version", secure.getMessageVersion() != null ? secure.getMessageVersion() : String.valueOf(secure.getVersion()));
        data.put("method_url", secure.getIssuerAcsUrl());
        data.put("method_data", secure.getPayerAuthenticationRequest());
        return data;
    }

    private ObjectNode mapAuthentication(ThreeDSecure secure) {
        ObjectNode data = MAPPER.createObjectNode();
        String status = secure.getStatus() != null ? secure.getStatus() : secure.getEnrolledStatus();
        data.put("server_trans_id", secure.getServerTransactionId());
        data.put("status", status);
        data.put("acs_reference_number", secure.getAcsReferenceNumber());
        data.put("acs_trans_id", secure.getAcsTransactionId());
        data.put("acs_challenge_url", secure.getIssuerAcsUrl());
        data.put("eci", secure.getEci());
        data.put("authentication_value", secure.getAuthenticationValue());
        data.put("ds_trans_ref", secure.getDirectoryServerTransactionId());
        data.put("message_version", secure.getMessageVersion());
        data.put("server_trans_ref", secure.getProviderServerTransRef() != null ? secure.getProviderServerTransRef() : secure.getServerTransactionId());
        if ("CHALLENGE_REQUIRED".equals(status)) {
            ObjectNode challenge = data.putObject("challenge");
            challenge.put("requestUrl", secure.getIssuerAcsUrl());
            challenge.put("encodedChallengeRequest", secure.getPayerAuthenticationRequest());
            challenge.put("messageType", secure.messageType != null ? secure.messageType : "creq");
        }
        return data;
    }

    private ObjectNode mapTransaction(Transaction transaction) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("transaction_id", transaction.getTransactionId());
        data.put("status", transaction.getResponseMessage());
        data.put("result_code", transaction.getResponseCode());
        data.put("amount", transaction.getBalanceAmount() != null ? transaction.getBalanceAmount().toPlainString() : null);
        data.put("currency", "");
        return data;
    }

    private String amount(JsonNode input) {
        return input.path("order").path("amount").asText(input.path("amount").asText("10.00"));
    }

    private BigDecimal amountDecimal(JsonNode input) {
        return new BigDecimal(amount(input));
    }

    private String currency(JsonNode input) {
        return input.path("order").path("currency").asText(input.path("currency").asText("GBP"));
    }

    private String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.path(key);
        return value.isMissingNode() || value.isNull() ? fallback : value.asText();
    }

    private String env(String key, String fallback) {
        String value = dotenv.get(key, null);
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String required(String key) {
        String value = env(key, null);
        if (value == null) {
            throw new IllegalStateException(key + " must be set");
        }
        return value;
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private ColorDepth colorDepth(int value) {
        if (value >= 48) return ColorDepth.FortyEightBit;
        if (value >= 32) return ColorDepth.ThirtyTwoBit;
        if (value >= 24) return ColorDepth.TwentyFourBit;
        if (value >= 16) return ColorDepth.SixteenBit;
        if (value >= 15) return ColorDepth.FifteenBit;
        if (value >= 8) return ColorDepth.EightBit;
        if (value >= 4) return ColorDepth.FourBit;
        if (value >= 2) return ColorDepth.TwoBit;
        return ColorDepth.OneBit;
    }

    private ChallengeWindowSize challengeWindow(String value) {
        return Arrays.stream(ChallengeWindowSize.values())
            .filter(item -> item.getValue().equals(value))
            .findFirst()
            .orElse(ChallengeWindowSize.FullScreen);
    }

    private MethodUrlCompletion methodCompletion(String value) {
        return Arrays.stream(MethodUrlCompletion.values())
            .filter(item -> item.getValue().equals(value))
            .findFirst()
            .orElse(MethodUrlCompletion.Unavailable);
    }

    private void writeSuccess(HttpServletResponse res, ObjectNode data, Object raw) throws IOException {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("success", true);
        out.set("data", data);
        out.set("raw", MAPPER.valueToTree(raw));
        writeJson(res, out);
    }

    private void writeJson(HttpServletResponse res, JsonNode node) throws IOException {
        res.getWriter().write(MAPPER.writeValueAsString(node));
    }

    private void writeError(HttpServletResponse res, Exception e) throws IOException {
        res.setStatus(500);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("success", false);
        out.put("error", e.getMessage());
        out.putObject("raw").put("type", e.getClass().getName());
        writeJson(res, out);
    }

    private void addCors(HttpServletResponse res) {
        res.setHeader("Access-Control-Allow-Origin", env("FRONTEND_ORIGIN", "http://localhost:8000"));
        res.setHeader("Vary", "Origin");
        res.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        res.setHeader("Access-Control-Allow-Headers", "Content-Type");
    }
}
