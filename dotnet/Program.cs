using GlobalPayments.Api;
using GlobalPayments.Api.Entities;
using GlobalPayments.Api.PaymentMethods;
using dotenv.net;

namespace CardPaymentSample;

/// <summary>
/// Card Payment Processing Application
/// 
/// This application demonstrates card payment processing using the Global Payments SDK.
/// It provides endpoints for configuration and payment processing, handling tokenized
/// card data to ensure secure payment processing.
/// </summary>
public class Program
{
    public static void Main(string[] args)
    {
        // Load environment variables from .env file
        DotEnv.Load();

        var builder = WebApplication.CreateBuilder(args);
        
        var app = builder.Build();

        // Configure static file serving for the payment form
        app.UseDefaultFiles();
        app.UseStaticFiles();
        
        // Configure the SDK on startup
        ConfigureGlobalPaymentsSDK();

        ConfigureEndpoints(app);
        
        var port = System.Environment.GetEnvironmentVariable("PORT") ?? "8000";
        app.Urls.Add($"http://0.0.0.0:{port}");
        
        app.Run();
    }

    /// <summary>
    /// Configures the Global Payments SDK with necessary credentials and settings.
    /// This must be called before processing any payments.
    /// </summary>
    private static void ConfigureGlobalPaymentsSDK()
    {
        ServicesContainer.ConfigureService(new PorticoConfig
        {
            SecretApiKey = System.Environment.GetEnvironmentVariable("SECRET_API_KEY"),
            DeveloperId = "000000",
            VersionNumber = "0000",
            ServiceUrl = "https://cert.api2.heartlandportico.com"
        });
    }

    /// <summary>
    /// Configures the application's HTTP endpoints for payment processing.
    /// </summary>
    /// <param name="app">The web application to configure</param>
    private static void ConfigureEndpoints(WebApplication app)
    {
        // Configure HTTP endpoints
        app.MapGet("/config", () => Results.Ok(new
        { 
            success = true,
            data = new {
                publicApiKey = System.Environment.GetEnvironmentVariable("PUBLIC_API_KEY")
            }
        }));

        ConfigurePaymentEndpoint(app);
    }

    /// <summary>
    /// Sanitizes postal code input by removing invalid characters.
    /// </summary>
    /// <param name="postalCode">The postal code to sanitize. Can be null.</param>
    /// <returns>
    /// A sanitized postal code containing only alphanumeric characters and hyphens,
    /// limited to 10 characters. Returns empty string if input is null or empty.
    /// </returns>
    private static string SanitizePostalCode(string postalCode)
    {
        if (string.IsNullOrEmpty(postalCode)) return string.Empty;
        
        // Remove any characters that aren't alphanumeric or hyphen
        var sanitized = new string(postalCode.Where(c => char.IsLetterOrDigit(c) || c == '-').ToArray());
        
        // Limit length to 10 characters
        return sanitized.Length > 10 ? sanitized[..10] : sanitized;
    }

    /// <summary>
    /// Configures the payment processing endpoint that handles card transactions.
    /// </summary>
    /// <param name="app">The web application to configure</param>
    private static void ConfigurePaymentEndpoint(WebApplication app)
    {
        app.MapPost("/process-payment", async (HttpContext context) =>
        {
            // Parse form data from the request
            var form = await context.Request.ReadFormAsync();
            var billingZip = form["billing_zip"].ToString();
            var token = form["payment_token"].ToString();
            var amountStr = form["amount"].ToString();

            // Validate required fields are present
            if (string.IsNullOrEmpty(token) || string.IsNullOrEmpty(billingZip) || string.IsNullOrEmpty(amountStr))
            {
                return Results.BadRequest(new {
                    success = false,
                    message = "Payment processing failed",
                    error = new {
                        code = "VALIDATION_ERROR",
                        details = "Missing required fields"
                    }
                });
            }

            // Validate and parse amount
            if (!decimal.TryParse(amountStr, out var amount) || amount <= 0)
            {
                return Results.BadRequest(new {
                    success = false,
                    message = "Payment processing failed",
                    error = new {
                        code = "VALIDATION_ERROR",
                        details = "Amount must be a positive number"
                    }
                });
            }

            // Initialize payment data using tokenized card information
            var card = new CreditCardData
            {
                Token = token
            };

            // Create billing address for AVS verification
            var address = new Address
            {
                PostalCode = SanitizePostalCode(billingZip)
            };

            try
            {
                // Process the payment transaction using the provided amount
                var response = card.Charge(amount)
                    .WithAllowDuplicates(true)
                    .WithCurrency("USD")
                    .WithAddress(address)
                    .Execute();

                // Verify transaction was successful
                if (response.ResponseCode != "00")
                {
                    return Results.BadRequest(new {
                        success = false,
                        message = "Payment processing failed",
                        error = new {
                            code = "PAYMENT_DECLINED",
                            details = response.ResponseMessage
                        }
                    });
                }

                // Return success response with transaction ID
                return Results.Ok(new
                {
                    success = true,
                    message = $"Payment successful! Transaction ID: {response.TransactionId}",
                    data = new {
                        transactionId = response.TransactionId
                    }
                });
            } 
            catch (ApiException ex)
            {
                // Handle payment processing errors
                return Results.BadRequest(new {
                    success = false,
                    message = "Payment processing failed",
                    error = new {
                        code = "API_ERROR",
                        details = ex.Message
                    }
                });
            }
        });
    }
}
