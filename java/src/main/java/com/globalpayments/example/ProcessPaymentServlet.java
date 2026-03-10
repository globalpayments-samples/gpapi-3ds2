package com.globalpayments.example;

import com.global.api.ServicesContainer;
import com.global.api.entities.Address;
import com.global.api.entities.Transaction;
import com.global.api.entities.exceptions.ApiException;
import com.global.api.entities.exceptions.ConfigurationException;
import com.global.api.paymentMethods.CreditCardData;
import com.global.api.serviceConfigs.PorticoConfig;
import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * Card Payment Processing Servlet
 * 
 * This servlet demonstrates card payment processing using the Global Payments SDK.
 * It provides endpoints for configuration and payment processing, handling 
 * tokenized card data to ensure secure payment processing.
 * 
 * Endpoints:
 * - GET /config: Returns the public API key for client-side tokenization
 * - POST /process-payment: Processes card payments using tokenized data
 * 
 * @author Global Payments
 * @version 1.0
 */

@WebServlet(urlPatterns = {"/process-payment", "/config"})
public class ProcessPaymentServlet extends HttpServlet {
    
    private static final long serialVersionUID = 1L;
    private final Dotenv dotenv = Dotenv.load();
    
    /**
     * Initializes the servlet and configures the Global Payments SDK.
     * This must be called before processing any payments.
     * 
     * @throws ServletException if there's an error initializing the servlet
     */
    @Override
    public void init() throws ServletException {
        try {
            // Configure the Global Payments SDK with credentials and settings
            PorticoConfig config = new PorticoConfig();
            config.setSecretApiKey(dotenv.get("SECRET_API_KEY"));
            config.setDeveloperId("000000");
            config.setVersionNumber("0000");
            config.setServiceUrl("https://cert.api2.heartlandportico.com");

            ServicesContainer.configureService(config);
        } catch (ConfigurationException e) {
            // Log configuration errors and propagate as ServletException
            throw new ServletException("Failed to configure Global Payments SDK", e);
        }
    }

    /**
     * Handles GET requests to /config endpoint.
     * Returns the public API key needed for client-side tokenization.
     *
     * @param request The HTTP request
     * @param response The HTTP response
     * @throws ServletException If there's an error in servlet processing
     * @throws IOException If there's an I/O error
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (request.getServletPath().equals("/config")) {
        response.setContentType("application/json");
        String publicKey = dotenv.get("PUBLIC_API_KEY");
        String jsonResponse = String.format(
            "{\"success\":true,\"data\":{\"publicApiKey\":\"%s\"}}", 
            publicKey
        );
        response.getWriter().write(jsonResponse);
        }
    }

    /**
     * Sanitizes postal code input by removing invalid characters.
     * Only allows alphanumeric characters and hyphens, limited to 10 characters.
     *
     * @param postalCode The postal code to sanitize, can be null
     * @return A sanitized postal code containing only alphanumeric characters
     *         and hyphens, limited to 10 characters. Returns empty string if input is null.
     */
    private String sanitizePostalCode(String postalCode) {
        if (postalCode == null) {
            return "";
        }
        String sanitized = postalCode.replaceAll("[^a-zA-Z0-9-]", "");
        return sanitized.length() > 10 ? sanitized.substring(0, 10) : sanitized;
    }

    /**
     * Handles POST requests to /process-payment endpoint.
     * Processes card payments using tokenized card data.
     *
     * @param request The HTTP request containing payment details
     * @param response The HTTP response
     * @throws ServletException If there's an error in servlet processing
     * @throws IOException If there's an I/O error
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        
        response.setContentType("application/json");

        try {
            // Validate and extract payment information
            String paymentToken = request.getParameter("payment_token");
            String billingZip = request.getParameter("billing_zip");
            String amountStr = request.getParameter("amount");

            if (paymentToken == null || billingZip == null || amountStr == null ||
                paymentToken.trim().isEmpty() || billingZip.trim().isEmpty() || amountStr.trim().isEmpty()) {
                throw new ApiException("Missing required fields");
            }

            // Validate and parse amount
            BigDecimal amount;
            try {
                amount = new BigDecimal(amountStr);
                if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ApiException("Amount must be a positive number");
                }
            } catch (NumberFormatException e) {
                throw new ApiException("Invalid amount format");
            }

            // Initialize payment data using tokenized card information
            CreditCardData card = new CreditCardData();
            card.setToken(paymentToken);

            // Create billing address for AVS verification
            Address address = new Address();
            address.setPostalCode(sanitizePostalCode(billingZip));

            // Process the payment transaction using the provided amount
            Transaction transaction = card.charge(amount)
                    .withAllowDuplicates(true)
                    .withCurrency("USD")
                    .withAddress(address)
                    .execute();

            // Verify transaction was successful
            if (!"00".equals(transaction.getResponseCode())) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                String errorResponse = String.format(
                    "{\"success\":false,\"message\":\"Payment processing failed\",\"error\":{\"code\":\"PAYMENT_DECLINED\",\"details\":\"%s\"}}", 
                    transaction.getResponseMessage()
                );
                response.getWriter().write(errorResponse);
                return;
            }

            // Return success response with transaction ID
            String successResponse = String.format(
                "{\"success\":true,\"message\":\"Payment successful! Transaction ID: %s\",\"data\":{\"transactionId\":\"%s\"}}", 
                transaction.getTransactionId(),
                transaction.getTransactionId()
            );
            response.getWriter().write(successResponse);

        } catch (ApiException e) {
            // Handle payment processing errors
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            String errorResponse = String.format(
                "{\"success\":false,\"message\":\"Payment processing failed\",\"error\":{\"code\":\"API_ERROR\",\"details\":\"%s\"}}", 
                e.getMessage()
            );
            response.getWriter().write(errorResponse);
        }
    }
}
