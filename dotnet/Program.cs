/**
 * GP-API 3DS2 Backend — .NET Minimal API
 *
 * Server-side Global Payments calls are made through the official .NET SDK.
 */

using System.Text.Json;
using System.Text.Json.Serialization;
using dotenv.net;
using GlobalPayments.Api;
using GlobalPayments.Api.Entities;
using GlobalPayments.Api.PaymentMethods;
using GlobalPayments.Api.Services;
using GPEnvironment = GlobalPayments.Api.Entities.Environment;

DotEnv.Load();

var builder = WebApplication.CreateBuilder(args);
var app = builder.Build();
var configured = false;
const string ConfigName = "gpapi-3ds-sample";
const string GpVersion = "2021-03-22";

var jsonOptions = new JsonSerializerOptions {
    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    ReferenceHandler = ReferenceHandler.IgnoreCycles
};

app.Use(async (ctx, next) =>
{
    ctx.Response.Headers["Access-Control-Allow-Origin"] = "*";
    ctx.Response.Headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS";
    ctx.Response.Headers["Access-Control-Allow-Headers"] = "Content-Type";
    if (ctx.Request.Method == "OPTIONS") {
        ctx.Response.StatusCode = 204;
        return;
    }
    await next();
});

app.MapGet("/api/health", () => Results.Ok(new {
    status = "ok",
    backend = "dotnet",
    version = "1.0.0",
    sdk = "GlobalPayments.Api"
}));

app.MapMethods("/3ds-method-notification", new[] { "GET", "POST" }, async (HttpRequest req) =>
{
    var data = await NotificationValue(req, "threeDSMethodData");
    return Results.Content(NotificationPage("handleMethodNotification", data), "text/html");
});

app.MapMethods("/3ds-challenge-notification", new[] { "GET", "POST" }, async (HttpRequest req) =>
{
    var data = await NotificationValue(req, "cres", "CRes");
    return Results.Content(NotificationPage("handleChallengeNotification", data), "text/html");
});

app.MapGet("/api/tokenization-config", () =>
{
    try {
        var token = GpApiService.GenerateTransactionKey(SdkConfig(tokenization: true));
        return Results.Ok(new {
            success = true,
            data = new {
                env = Env("GP_API_ENVIRONMENT", "sandbox"),
                accessToken = token.Token,
                accountName = Env("GP_TOKENIZATION_ACCOUNT_NAME", token.TokenizationAccountName ?? Env("GP_ACCOUNT_NAME", "transaction_processing")),
                merchantId = (string?)null,
                apiVersion = GpVersion
            }
        });
    } catch (Exception ex) {
        return SdkError(ex);
    }
});

app.MapPost("/api/check-enrollment", async (HttpRequest req) =>
{
    try {
        EnsureConfigured();
        var input = await JsonDocument.ParseAsync(req.Body);
        var secure = Secure3dService.CheckEnrollment(PaymentMethod(input.RootElement))
            .WithAmount(AmountDecimal(input.RootElement))
            .WithCurrency(Currency(input.RootElement))
            .Execute(ConfigName);
        return Results.Json(new { success = true, data = MapEnrollment(secure), raw = secure }, jsonOptions);
    } catch (Exception ex) {
        return SdkError(ex);
    }
});

app.MapPost("/api/initiate-auth", async (HttpRequest req) =>
{
    try {
        EnsureConfigured();
        var input = (await JsonDocument.ParseAsync(req.Body)).RootElement;
        var seed = new ThreeDSecure { ServerTransactionId = Get(input, "server_trans_id") };
        var secure = Secure3dService.InitiateAuthentication(PaymentMethod(input), seed)
            .WithAmount(AmountDecimal(input))
            .WithCurrency(Currency(input))
            .WithOrderCreateDate(DateTime.Now)
            .WithAddress(ShippingAddress(), AddressType.Shipping)
            .WithAuthenticationSource(AuthenticationSource.BROWSER)
            .WithBrowserData(BrowserDataFrom(input.TryGetProperty("browser_data", out var bd) ? bd : default))
            .WithMethodUrlCompletion(MethodCompletion(Get(input, "method_url_completion", "UNAVAILABLE")))
            .Execute(ConfigName);
        return Results.Json(new { success = true, data = MapAuthentication(secure), raw = secure }, jsonOptions);
    } catch (Exception ex) {
        return SdkError(ex);
    }
});

app.MapPost("/api/get-auth-result", async (HttpRequest req) =>
{
    try {
        EnsureConfigured();
        var input = (await JsonDocument.ParseAsync(req.Body)).RootElement;
        var serverTransId = Get(input, "server_trans_id").Trim();
        if (string.IsNullOrWhiteSpace(serverTransId)) {
            return Results.BadRequest(new { success = false, error = "server_trans_id is required" });
        }
        var secure = Secure3dService.GetAuthenticationData()
            .WithServerTransactionId(serverTransId)
            .WithAmount(AmountDecimal(input))
            .Execute(ConfigName);
        return Results.Json(new { success = true, data = MapAuthentication(secure), raw = secure }, jsonOptions);
    } catch (Exception ex) {
        return SdkError(ex);
    }
});

app.MapPost("/api/authorize-payment", async (HttpRequest req) =>
{
    try {
        EnsureConfigured();
        var input = (await JsonDocument.ParseAsync(req.Body)).RootElement;
        var authenticationId = Get(input, "authentication_id",
            input.TryGetProperty("three_ds", out var tds)
                ? Get(tds, "authentication_id", Get(tds, "server_trans_ref"))
                : "");

        ThreeDSecure? secure = null;
        if (!string.IsNullOrWhiteSpace(authenticationId)) {
            secure = Secure3dService.GetAuthenticationData()
                .WithServerTransactionId(authenticationId)
                .WithAmount(AmountDecimal(input))
                .Execute(ConfigName);
        }

        var card = PaymentMethod(input);
        if (secure != null) {
            card.ThreeDSecure = secure;
        }

        var transaction = card.Charge(AmountDecimal(input))
            .WithCurrency(Currency(input))
            .Execute(ConfigName);
        return Results.Json(new { success = true, data = MapTransaction(transaction), raw = transaction }, jsonOptions);
    } catch (Exception ex) {
        return SdkError(ex);
    }
});

GpApiConfig SdkConfig(bool tokenization = false)
{
    var config = new GpApiConfig {
        AppId = Required("GP_APP_ID"),
        AppKey = Required("GP_APP_KEY"),
        Environment = string.Equals(Env("GP_API_ENVIRONMENT", "sandbox"), "production", StringComparison.OrdinalIgnoreCase)
            ? GPEnvironment.PRODUCTION
            : GPEnvironment.TEST,
        Country = Env("GP_COUNTRY", "GB"),
        Channel = Channel.CardNotPresent,
        MerchantId = Env("GP_PARTNER_MERCHANT_ID", null),
        MethodNotificationUrl = Env("METHOD_NOTIFICATION_URL", null),
        ChallengeNotificationUrl = Env("CHALLENGE_NOTIFICATION_URL", null),
        MerchantContactUrl = Env("GP_MERCHANT_CONTACT_URL", "https://developer.globalpay.com/"),
        AccessTokenInfo = new AccessTokenInfo {
            TransactionProcessingAccountName = Env("GP_ACCOUNT_NAME", "transaction_processing"),
            TransactionProcessingAccountID = Env("GP_ACCOUNT_ID", null),
            TokenizationAccountName = Env("GP_TOKENIZATION_ACCOUNT_NAME", null),
        }
    };

    if (tokenization) {
        config.Permissions = new[] { "PMT_POST_Create_Single" };
        config.IntervalToExpire = IntervalToExpire.TEN_MINUTES;
    }

    return config;
}

void EnsureConfigured()
{
    if (configured) {
        return;
    }
    ServicesContainer.ConfigureService(SdkConfig(), ConfigName);
    configured = true;
}

CreditCardData PaymentMethod(JsonElement input)
{
    var card = new CreditCardData();
    var token = Get(input, "payment_method_id");
    if (!string.IsNullOrWhiteSpace(token)) {
        card.Token = token;
    } else {
        card.Number = Get(input, "card_number");
        card.ExpMonth = IntValue(Get(input, "exp_month"));
        card.ExpYear = IntValue(Get(input, "exp_year"));
        card.Cvn = Get(input, "cvn");
    }
    card.CardHolderName = Get(input, "cardholder_name", "Test User");
    return card;
}

BrowserData BrowserDataFrom(JsonElement input)
{
    return new BrowserData {
        AcceptHeader = Get(input, "accept_header", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
        ColorDepth = ColorDepthFrom(Get(input, "color_depth", "24")),
        IpAddress = Get(input, "ip", "127.0.0.1"),
        JavaEnabled = Bool(input, "java_enabled", false),
        JavaScriptEnabled = Bool(input, "javascript_enabled", true),
        Language = Get(input, "language", "en-GB"),
        ScreenHeight = Int(input, "screen_height", 1080),
        ScreenWidth = Int(input, "screen_width", 1920),
        ChallengeWindowSize = ChallengeWindow(Get(input, "challenge_window_size", "FULL_SCREEN")),
        Timezone = Get(input, "timezone", "0"),
        UserAgent = Get(input, "user_agent", "Mozilla/5.0")
    };
}

Address ShippingAddress() => new() {
    StreetAddress1 = "1 Test Street",
    City = "London",
    PostalCode = "SW1A 1AA",
    CountryCode = "826"
};

object MapEnrollment(ThreeDSecure secure) => new {
    server_trans_id = secure.ServerTransactionId,
    server_trans_ref = secure.ProviderServerTransRef ?? secure.ServerTransactionId,
    enrolled = secure.Enrolled,
    message_version = secure.MessageVersion ?? secure.Version?.ToString(),
    method_url = secure.IssuerAcsUrl,
    method_data = secure.PayerAuthenticationRequest,
};

object MapAuthentication(ThreeDSecure secure)
{
    var status = secure.Status ?? secure.Enrolled;
    return new {
        server_trans_id = secure.ServerTransactionId,
        status,
        acs_reference_number = secure.AcsReferenceNumber,
        acs_trans_id = secure.AcsTransactionId,
        acs_challenge_url = secure.IssuerAcsUrl,
        eci = secure.Eci,
        authentication_value = secure.AuthenticationValue,
        ds_trans_ref = secure.DirectoryServerTransactionId,
        message_version = secure.MessageVersion,
        server_trans_ref = secure.ProviderServerTransRef ?? secure.ServerTransactionId,
        challenge = status == "CHALLENGE_REQUIRED" ? new {
            requestUrl = secure.IssuerAcsUrl,
            encodedChallengeRequest = secure.PayerAuthenticationRequest,
            messageType = secure.MessageType ?? "creq"
        } : null
    };
}

object MapTransaction(Transaction transaction) => new {
    transaction_id = transaction.TransactionId,
    status = transaction.ResponseMessage,
    result_code = transaction.ResponseCode,
    amount = transaction.BalanceAmount,
    currency = (string?)null
};

string Amount(JsonElement input) =>
    input.TryGetProperty("order", out var order) && order.TryGetProperty("amount", out var amount)
        ? amount.ToString()
        : Get(input, "amount", "10.00");

decimal AmountDecimal(JsonElement input) =>
    decimal.TryParse(Amount(input), out var parsed) ? parsed : 10.00m;

string Currency(JsonElement input) =>
    input.TryGetProperty("order", out var order) && order.TryGetProperty("currency", out var currency)
        ? currency.ToString()
        : Get(input, "currency", "GBP");

string Get(JsonElement input, string key, string fallback = "")
{
    if (input.ValueKind == JsonValueKind.Undefined || input.ValueKind == JsonValueKind.Null) {
        return fallback;
    }
    return input.TryGetProperty(key, out var value) && value.ValueKind != JsonValueKind.Null
        ? value.ToString()
        : fallback;
}

bool Bool(JsonElement input, string key, bool fallback) =>
    bool.TryParse(Get(input, key, fallback ? "true" : "false"), out var parsed) ? parsed : fallback;

int Int(JsonElement input, string key, int fallback) =>
    int.TryParse(Get(input, key, fallback.ToString()), out var parsed) ? parsed : fallback;

int IntValue(string value) =>
    int.TryParse(value, out var parsed) ? parsed : 0;

ColorDepth ColorDepthFrom(string value)
{
    var numeric = int.TryParse(value, out var parsed) ? parsed : 24;
    return numeric switch {
        >= 48 => ColorDepth.FORTY_EIGHT_BITS,
        >= 32 => ColorDepth.THIRTY_TWO_BITS,
        >= 24 => ColorDepth.TWENTY_FOUR_BITS,
        >= 16 => ColorDepth.SIXTEEN_BITS,
        >= 15 => ColorDepth.FIFTEEN_BITS,
        >= 8 => ColorDepth.EIGHT_BITS,
        >= 4 => ColorDepth.FOUR_BITS,
        >= 2 => ColorDepth.TWO_BITS,
        _ => ColorDepth.ONE_BIT
    };
}

ChallengeWindowSize ChallengeWindow(string value) =>
    Enum.TryParse<ChallengeWindowSize>(value, out var parsed) ? parsed : ChallengeWindowSize.FULL_SCREEN;

MethodUrlCompletion MethodCompletion(string value) =>
    Enum.TryParse<MethodUrlCompletion>(value, out var parsed) ? parsed : MethodUrlCompletion.UNAVAILABLE;

IResult SdkError(Exception ex) => Results.Json(new {
    success = false,
    error = ex.Message,
    raw = new { type = ex.GetType().FullName }
}, statusCode: 500);

string Env(string name, string? fallback) =>
    System.Environment.GetEnvironmentVariable(name) is { Length: > 0 } value ? value : fallback ?? "";

string Required(string name) =>
    Env(name, null) is { Length: > 0 } value ? value : throw new InvalidOperationException($"{name} must be set");

async Task<string> NotificationValue(HttpRequest req, params string[] names)
{
    foreach (var name in names) {
        if (req.Query.TryGetValue(name, out var queryValue)) {
            return queryValue.ToString();
        }
    }
    if (req.HasFormContentType) {
        var form = await req.ReadFormAsync();
        foreach (var name in names) {
            if (form.TryGetValue(name, out var formValue)) {
                return formValue.ToString();
            }
        }
    }
    return "";
}

string NotificationPage(string handler, string data)
{
    var origin = Env("FRONTEND_ORIGIN", "http://localhost:8000");
    return $"""
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>3DS Notification</title>
  <script src="https://cdn.jsdelivr.net/npm/globalpayments-3ds@1.8.7/dist/globalpayments-3ds.min.js"></script>
</head>
<body>
<script>
  window.GlobalPayments?.ThreeDSecure?.{handler}({JsonSerializer.Serialize(data)}, {JsonSerializer.Serialize(origin)});
</script>
</body>
</html>
""";
}

var port = System.Environment.GetEnvironmentVariable("GP_SAMPLE_PORT") ?? System.Environment.GetEnvironmentVariable("PORT") ?? "8080";
app.Urls.Add($"http://0.0.0.0:{port}");
app.Run();
