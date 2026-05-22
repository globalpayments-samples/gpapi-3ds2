/**
 * GP-API 3DS2 Backend — .NET Minimal API
 *
 * Uses HttpClient directly (no GP SDK) to call GP-API UCP endpoints.
 * Token is cached in memory with expiry tracking.
 */

using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using dotenv.net;

DotEnv.Load();

var builder = WebApplication.CreateBuilder(args);
var app     = builder.Build();
var jsonOptions = new JsonSerializerOptions { DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull };

// CORS — allow frontend on any origin in dev
app.Use(async (ctx, next) =>
{
    ctx.Response.Headers["Access-Control-Allow-Origin"]  = "*";
    ctx.Response.Headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
    ctx.Response.Headers["Access-Control-Allow-Headers"] = "Content-Type";
    if (ctx.Request.Method == "OPTIONS") { ctx.Response.StatusCode = 204; return; }
    await next();
});

// ─── Token cache ─────────────────────────────────────────────────────────────

string? cachedToken     = null;
long    tokenExpiresAt  = 0; // Unix ms

async Task<JsonElement> GenerateAccessTokenAsync(HttpClient http, Dictionary<string, object>? extra = null)
{
    var appId  = Environment.GetEnvironmentVariable("GP_APP_ID")  ?? throw new Exception("GP_APP_ID not set");
    var appKey = Environment.GetEnvironmentVariable("GP_APP_KEY") ?? throw new Exception("GP_APP_KEY not set");

    var nonce  = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'");
    var secret = Convert.ToHexString(SHA512.HashData(Encoding.UTF8.GetBytes($"{nonce}{appKey}"))).ToLower();

    var tokenRequest = new Dictionary<string, object>
    {
        ["app_id"] = appId,
        ["nonce"] = nonce,
        ["secret"] = secret,
        ["grant_type"] = "client_credentials",
    };
    if (extra != null)
        foreach (var item in extra) tokenRequest[item.Key] = item.Value;

    var body = JsonSerializer.Serialize(tokenRequest);
    var req  = new HttpRequestMessage(HttpMethod.Post, "/ucp/accesstoken")
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json")
    };
    req.Headers.Add("X-GP-Version", "2021-03-22");

    var resp = await http.SendAsync(req);
    var json = await resp.Content.ReadAsStringAsync();
    var doc  = JsonDocument.Parse(json).RootElement;

    if (!resp.IsSuccessStatusCode)
        throw new Exception($"Token generation failed ({resp.StatusCode}): {json}");

    return doc.Clone();
}

async Task<string> GetAccessTokenAsync(HttpClient http)
{
    if (cachedToken != null && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() < tokenExpiresAt)
        return cachedToken;

    var doc = await GenerateAccessTokenAsync(http);
    cachedToken    = doc.GetProperty("token").GetString();
    var expiresIn  = doc.GetProperty("seconds_to_expire").GetInt32();
    tokenExpiresAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + (expiresIn - 60) * 1000L;
    return cachedToken!;
}

// ─── GP-API request helper ────────────────────────────────────────────────────

using var httpClient = new HttpClient(new HttpClientHandler { AutomaticDecompression = System.Net.DecompressionMethods.All })
    { BaseAddress = new Uri("https://apis.sandbox.globalpay.com") };

async Task<(JsonElement root, bool ok, int status)> GpRequest(string method, string path, object? body = null)
{
    var token = await GetAccessTokenAsync(httpClient);
    var req   = new HttpRequestMessage(new HttpMethod(method), path);
    req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
    req.Headers.Add("X-GP-Version", "2021-03-22");

    if (body != null)
        req.Content = new StringContent(JsonSerializer.Serialize(body, jsonOptions), Encoding.UTF8, "application/json");

    var resp = await httpClient.SendAsync(req);
    var json = await resp.Content.ReadAsStringAsync();
    var root = JsonDocument.Parse(json.Length > 0 ? json : "{}").RootElement;
    return (root, resp.IsSuccessStatusCode, (int)resp.StatusCode);
}

static string ToMinorUnits(string amount) =>
    ((int)Math.Round(double.Parse(amount) * 100)).ToString();

static string TwoDigitYear(string year) =>
    year.Length > 2 ? year[^2..] : year;

IResult GpError(JsonElement root, int status)
{
    var msg = root.TryGetProperty("error", out var e) && e.TryGetProperty("message", out var m)
        ? m.GetString() : $"GP-API error {status}";
    return Results.Json(new
    {
        success         = false,
        error           = msg,
        gp_error_code   = root.TryGetProperty("error", out var e2) && e2.TryGetProperty("code",   out var cod) ? cod.GetString() : null,
        gp_error_detail = root.TryGetProperty("error", out var e3) && e3.TryGetProperty("detail", out var det) ? det.GetString() : null,
        raw             = root
    }, statusCode: status);
}

// ─── Routes ──────────────────────────────────────────────────────────────────

app.MapGet("/api/health", () => Results.Ok(new { status = "ok", backend = "dotnet", version = "1.0.0" }));

app.MapGet("/api/tokenization-config", async () =>
{
    try
    {
        var token = await GenerateAccessTokenAsync(httpClient, new Dictionary<string, object>
        {
            ["permissions"] = new[] { "PMT_POST_Create_Single" },
            ["restricted_token"] = "YES",
            ["interval_to_expire"] = "10_MINUTES",
        });
        string? tokenizationAccount = null;
        var tokenizationAccountOverride = Environment.GetEnvironmentVariable("GP_TOKENIZATION_ACCOUNT_NAME");
        if (token.TryGetProperty("scope", out var scope) &&
            scope.TryGetProperty("accounts", out var accounts) &&
            accounts.ValueKind == JsonValueKind.Array &&
            accounts.GetArrayLength() > 0 &&
            accounts[0].TryGetProperty("name", out var accountNameFromScope))
            tokenizationAccount = accountNameFromScope.GetString();

        return Results.Ok(new
        {
            success = true,
            data = new
            {
                env = Environment.GetEnvironmentVariable("GP_API_ENVIRONMENT") ?? "sandbox",
                accessToken = token.GetProperty("token").GetString(),
                accountName = !string.IsNullOrWhiteSpace(tokenizationAccountOverride)
                    ? tokenizationAccountOverride
                    : tokenizationAccount
                    ?? Environment.GetEnvironmentVariable("GP_ACCOUNT_NAME")
                    ?? "transaction_processing",
                merchantId = Environment.GetEnvironmentVariable("GP_MERCHANT_ID"),
                apiVersion = "2021-03-22"
            }
        });
    }
    catch (Exception e)
    {
        return Results.Json(new { success = false, error = e.Message }, statusCode: 500);
    }
});

app.MapPost("/api/check-enrollment", async (HttpRequest req) =>
{
    var root        = (await JsonDocument.ParseAsync(req.Body)).RootElement;
    string Get(string k, string def = "") => root.TryGetProperty(k, out var v) ? v.GetString() ?? def : def;
    var cardNumber  = Get("card_number");
    var expMonth    = Get("exp_month");
    var expYear     = Get("exp_year");
    var paymentMethodId = Get("payment_method_id");
    var accountName  = Environment.GetEnvironmentVariable("GP_ACCOUNT_NAME") ?? "transaction_processing";
    var challengeUrl = Environment.GetEnvironmentVariable("CHALLENGE_NOTIFICATION_URL");
    var methodUrl    = Environment.GetEnvironmentVariable("METHOD_NOTIFICATION_URL");

    var accountId   = Environment.GetEnvironmentVariable("GP_ACCOUNT_ID");
    var merchantId  = Environment.GetEnvironmentVariable("GP_MERCHANT_ID");

    object paymentMethod = !string.IsNullOrEmpty(paymentMethodId)
        ? new { id = paymentMethodId }
        : new
        {
            entry_mode = "ECOM",
            card = new { number = cardNumber, expiry_month = expMonth, expiry_year = TwoDigitYear(expYear) }
        };

    var payload = new
    {
        account_name = accountName, account_id = accountId, merchant_id = merchantId,
        channel = "CNP", country = "GB",
        amount = "1000", currency = "GBP", reference = Guid.NewGuid().ToString(),
        payment_method = paymentMethod,
        three_ds = new { source = "BROWSER", preference = "NO_PREFERENCE", message_version = "2.2.0" },
        notifications = new { challenge_return_url = challengeUrl, three_ds_method_return_url = methodUrl }
    };

    var (r, ok, status) = await GpRequest("POST", "/ucp/authentications", payload);
    if (!ok) return GpError(r, status);

    string? mUrl = null, mData = null;
    if (r.TryGetProperty("three_ds", out var tds) &&
        tds.TryGetProperty("method_url", out var mu) &&
        mu.ValueKind != JsonValueKind.Null)
    {
        mUrl  = mu.GetString();
        var mJson = JsonSerializer.Serialize(new { threeDSServerTransID = r.GetProperty("id").GetString(), methodNotificationURL = methodUrl });
        mData = Convert.ToBase64String(Encoding.UTF8.GetBytes(mJson));
    }

    var tds2 = r.TryGetProperty("three_ds", out var t2) ? t2 : default;
    string? Tds2(string k) => tds2.ValueKind != JsonValueKind.Undefined && tds2.TryGetProperty(k, out var v) ? v.GetString() : null;

    return Results.Ok(new
    {
        success = true,
        data = new
        {
            server_trans_id  = r.GetProperty("id").GetString(),
            server_trans_ref = Tds2("server_trans_ref"),
            enrolled         = Tds2("enrolled_status"),
            message_version  = Tds2("message_version"),
            method_url       = mUrl,
            method_data      = mData
        },
        raw = r
    });
});

app.MapPost("/api/initiate-auth", async (HttpRequest req) =>
{
    var root = (await JsonDocument.ParseAsync(req.Body)).RootElement;

    string Get(string k, string def = "") => root.TryGetProperty(k, out var v) ? v.GetString() ?? def : def;
    var serverTransIdRaw    = Get("server_trans_id");
    var authenticationId    = serverTransIdRaw.Trim();
    var serverTransId       = authenticationId.StartsWith("AUT_") ? authenticationId[4..] : authenticationId;
    var messageVersion      = Get("message_version", "2.1.0");
    var methodUrlCompletion = Get("method_url_completion", "UNAVAILABLE");
    var paymentMethodId     = Get("payment_method_id");
    var cardNumber          = Get("card_number");
    var expMonth            = Get("exp_month");
    var expYear             = Get("exp_year");
    var cardholderName      = Get("cardholder_name", "Test User");
    var order               = root.TryGetProperty("order", out var o) ? o : default;
    var amount              = order.ValueKind != JsonValueKind.Undefined && order.TryGetProperty("amount",   out var a) ? a.GetString()! : "10.00";
    var currency            = order.ValueKind != JsonValueKind.Undefined && order.TryGetProperty("currency", out var c) ? c.GetString()! : "GBP";
    var bd                  = root.TryGetProperty("browser_data", out var bde) ? bde : default;

    string Bd(string k, string def) => bd.ValueKind != JsonValueKind.Undefined && bd.TryGetProperty(k, out var v) ? v.ToString() : def;

    var accountName  = Environment.GetEnvironmentVariable("GP_ACCOUNT_NAME") ?? "transaction_processing";
    var accountId2   = Environment.GetEnvironmentVariable("GP_ACCOUNT_ID");
    var merchantId2  = Environment.GetEnvironmentVariable("GP_MERCHANT_ID");
    var challengeUrl = Environment.GetEnvironmentVariable("CHALLENGE_NOTIFICATION_URL");
    var methodUrl2   = Environment.GetEnvironmentVariable("METHOD_NOTIFICATION_URL");

    object paymentMethod = !string.IsNullOrEmpty(paymentMethodId)
        ? new { id = paymentMethodId, name = cardholderName, entry_mode = "ECOM" }
        : new
        {
            entry_mode = "ECOM",
            card = new { number = cardNumber, expiry_month = expMonth, expiry_year = TwoDigitYear(expYear), full_name = cardholderName }
        };

    var payload = new
    {
        account_name = accountName, account_id = accountId2, merchant_id = merchantId2,
        channel = "CNP", country = "GB",
        amount = ToMinorUnits(amount), currency, reference = Guid.NewGuid().ToString(),
        payment_method = paymentMethod,
        three_ds = new
        {
            source = "BROWSER", preference = "NO_PREFERENCE", message_version = messageVersion,
            server_trans_ref = serverTransId, method_url_completion = methodUrlCompletion
        },
        order = new
        {
            amount = ToMinorUnits(amount), currency, reference = Guid.NewGuid().ToString(),
            address_indicator = false, date_time_created = DateTime.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'")
        },
        payer = new
        {
            email = "test@example.com",
            billing_address = new { line1 = "1 Test Street", city = "London", postal_code = "SW1A 1AA", country = "826" }
        },
        browser_data = new
        {
            accept_header         = Bd("accept_header",        "text/html,application/xhtml+xml"),
            color_depth           = Bd("color_depth",          "24"),
            ip                    = Bd("ip",                   "123.123.123.123"),
            java_enabled          = Bd("java_enabled",         "false"),
            javascript_enabled    = Bd("javascript_enabled",   "true"),
            language              = Bd("language",             "en-GB"),
            screen_height         = Bd("screen_height",        "1080"),
            screen_width          = Bd("screen_width",         "1920"),
            challenge_window_size = Bd("challenge_window_size","FULL_SCREEN"),
            timezone              = Bd("timezone",             "0"),
            user_agent            = Bd("user_agent",           "Mozilla/5.0")
        },
        notifications = new { challenge_return_url = challengeUrl, three_ds_method_return_url = methodUrl2 }
    };

    var (r, ok, status) = await GpRequest("POST", $"/ucp/authentications/{Uri.EscapeDataString(authenticationId)}/initiate", payload);
    if (!ok) return GpError(r, status);

    var tds = r.TryGetProperty("three_ds", out var t) ? t : default;
    string? Tds(string k) => tds.ValueKind != JsonValueKind.Undefined && tds.TryGetProperty(k, out var v) ? v.GetString() : null;

    return Results.Ok(new
    {
        success = true,
        data = new
        {
            server_trans_id      = r.GetProperty("id").GetString(),
            status               = r.TryGetProperty("status", out var s) ? s.GetString() : null,
            acs_reference_number = Tds("acs_reference_number"),
            acs_trans_id         = Tds("acs_trans_id"),
            acs_signed_content   = Tds("acs_signed_content"),
            acs_challenge_url    = Tds("acs_challenge_url") ?? Tds("challenge_value"),
            eci                  = Tds("eci"),
            authentication_value = Tds("authentication_value"),
            ds_trans_ref         = Tds("ds_trans_ref"),
            message_version      = Tds("message_version"),
            server_trans_ref     = Tds("server_trans_ref") ?? r.GetProperty("id").GetString()
        },
        raw = r
    });
});

app.MapPost("/api/get-auth-result", async (HttpRequest req) =>
{
    var root          = (await JsonDocument.ParseAsync(req.Body)).RootElement;
    var serverTransIdRaw = root.GetProperty("server_trans_id").GetString();
    var serverTransId    = serverTransIdRaw?.Trim();

    if (string.IsNullOrEmpty(serverTransId))
        return Results.BadRequest(new { success = false, error = "server_trans_id is required" });

    var (r, ok, status) = await GpRequest("GET", $"/ucp/authentications/{Uri.EscapeDataString(serverTransId)}/result");
    if (!ok) return GpError(r, status);

    var tds = r.TryGetProperty("three_ds", out var t) ? t : default;
    string? Tds(string k) => tds.ValueKind != JsonValueKind.Undefined && tds.TryGetProperty(k, out var v) ? v.GetString() : null;

    return Results.Ok(new
    {
        success = true,
        data = new
        {
            status               = r.TryGetProperty("status", out var s) ? s.GetString() : null,
            eci                  = Tds("eci"),
            authentication_value = Tds("authentication_value"),
            ds_trans_ref         = Tds("ds_trans_ref"),
            message_version      = Tds("message_version"),
            server_trans_ref     = Tds("server_trans_ref") ?? r.GetProperty("id").GetString()
        },
        raw = r
    });
});

app.MapPost("/api/authorize-payment", async (HttpRequest req) =>
{
    var root = (await JsonDocument.ParseAsync(req.Body)).RootElement;

    string Get(string k, string def = "") => root.TryGetProperty(k, out var v) ? v.GetString() ?? def : def;
    var cardNumber = Get("card_number");
    var expMonth   = Get("exp_month");
    var expYear    = Get("exp_year");
    var cvn        = Get("cvn");
    var amount     = Get("amount", "10.00");
    var currency   = Get("currency", "GBP");
    var paymentMethodId = Get("payment_method_id");
    var cardholderName = Get("cardholder_name", "Test User");
    var tds3       = root.TryGetProperty("three_ds", out var t3) ? t3 : default;
    string? Td(string k) => tds3.ValueKind != JsonValueKind.Undefined && tds3.TryGetProperty(k, out var v) ? v.GetString() : null;

    var accountName = Environment.GetEnvironmentVariable("GP_ACCOUNT_NAME") ?? "transaction_processing";
    var accountId = Environment.GetEnvironmentVariable("GP_ACCOUNT_ID");
    var merchantId = Environment.GetEnvironmentVariable("GP_MERCHANT_ID");

    object paymentMethod = !string.IsNullOrEmpty(paymentMethodId)
        ? new
        {
            id = paymentMethodId,
            name = cardholderName,
            entry_mode = "ECOM",
            authentication = new { id = Get("authentication_id", Td("authentication_id") ?? Td("server_trans_ref") ?? "") }
        }
        : new
        {
            entry_mode = "ECOM",
            card = new { number = cardNumber, expiry_month = expMonth, expiry_year = TwoDigitYear(expYear), cvv = cvn }
        };

    var payload = new
    {
        account_name = accountName, account_id = accountId, merchant_id = merchantId,
        channel = "CNP", type = "SALE",
        amount = ToMinorUnits(amount), currency, reference = Guid.NewGuid().ToString(), country = "GB",
        payment_method = paymentMethod,
        three_ds = !string.IsNullOrEmpty(paymentMethodId) ? null : new
        {
            source               = "BROWSER",
            authentication_value = Td("authentication_value"),
            server_trans_ref     = Td("server_trans_ref"),
            ds_trans_ref         = Td("ds_trans_ref"),
            eci                  = Td("eci"),
            message_version      = Td("message_version") ?? "2.2.0"
        }
    };

    var (r, ok, status) = await GpRequest("POST", "/ucp/transactions", payload);
    if (!ok) return GpError(r, status);

    return Results.Ok(new
    {
        success = true,
        data = new
        {
            transaction_id = r.GetProperty("id").GetString(),
            status         = r.TryGetProperty("status",   out var s)  ? s.GetString()  : null,
            result_code    = r.TryGetProperty("action",   out var ac) && ac.TryGetProperty("result_code", out var rc) ? rc.GetString() : null,
            amount         = r.TryGetProperty("amount",   out var am) ? am.GetString()  : null,
            currency       = r.TryGetProperty("currency", out var cu) ? cu.GetString()  : null
        },
        raw = r
    });
});

var port = Environment.GetEnvironmentVariable("GP_SAMPLE_PORT") ?? Environment.GetEnvironmentVariable("PORT") ?? "8080";
app.Urls.Add($"http://0.0.0.0:{port}");
app.Run();
