// Package main implements a card payment processing server using the Global Payments SDK.
// It provides endpoints for configuration and payment processing, handling tokenized
// card data to ensure secure payment processing.
package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"regexp"
	"strconv"

	"github.com/globalpayments/go-sdk/api"
	"github.com/globalpayments/go-sdk/api/entities/base"
	"github.com/globalpayments/go-sdk/api/entities/transactions"
	"github.com/globalpayments/go-sdk/api/paymentmethods"
	"github.com/globalpayments/go-sdk/api/serviceconfigs"
	"github.com/globalpayments/go-sdk/api/utils/stringutils"
	"github.com/joho/godotenv"
)

// Config represents the configuration response sent to the client
type Config struct {
	PublicApiKey string `json:"publicApiKey"`
}

// Response represents a standardized API response
type Response struct {
	Success bool        `json:"success"`
	Message string      `json:"message,omitempty"`
	Data    interface{} `json:"data,omitempty"`
	Error   *ErrorInfo  `json:"error,omitempty"`
}

// ErrorInfo represents error details in the response
type ErrorInfo struct {
	Code    string `json:"code"`
	Details string `json:"details"`
}

// PaymentRequest represents the expected payment processing request payload
type PaymentRequest struct {
	PaymentToken string `json:"payment_token"`
	BillingZip   string `json:"billing_zip"`
}

// sanitizePostalCode removes invalid characters from the postal code input.
// It only allows alphanumeric characters and hyphens, limiting the length to 10 characters.
// This handles both US (12345, 12345-6789) and international postal codes.
func sanitizePostalCode(postalCode string) string {
	// Remove any characters that aren't alphanumeric or hyphen
	reg := regexp.MustCompile("[^a-zA-Z0-9-]")
	sanitized := reg.ReplaceAllString(postalCode, "")
	// Limit length to 10 characters
	if len(sanitized) > 10 {
		return sanitized[:10]
	}
	return sanitized
}

// handleConfig handles the /config endpoint
func handleConfig(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	response := Response{
		Success: true,
		Data: Config{
			PublicApiKey: os.Getenv("PUBLIC_API_KEY"),
		},
	}
	json.NewEncoder(w).Encode(response)
}

// handlePayment handles the /process-payment endpoint
func handlePayment(w http.ResponseWriter, r *http.Request) {
	// Ensure endpoint only accepts POST requests
	if r.Method != http.MethodPost {
		http.Error(w, "Method not allowed", http.StatusMethodNotAllowed)
		return
	}

	// Parse and validate the form data
	if err := r.ParseForm(); err != nil {
		http.Error(w, "Error parsing form data", http.StatusBadRequest)
		return
	}

	// Extract payment information from form
	paymentToken := r.Form.Get("payment_token")
	billingZip := r.Form.Get("billing_zip")
	amountStr := r.Form.Get("amount")

	// Validate required fields are present
	if paymentToken == "" || billingZip == "" || amountStr == "" {
		w.Header().Set("Content-Type", "application/json")
		errorResponse := Response{
			Success: false,
			Message: "Payment processing failed",
			Error: &ErrorInfo{
				Code:    "VALIDATION_ERROR",
				Details: "Missing required fields",
			},
		}
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(errorResponse)
		return
	}

	// Validate and parse amount
	amount, err := strconv.ParseFloat(amountStr, 64)
	if err != nil || amount <= 0 {
		w.Header().Set("Content-Type", "application/json")
		errorResponse := Response{
			Success: false,
			Message: "Payment processing failed",
			Error: &ErrorInfo{
				Code:    "VALIDATION_ERROR",
				Details: "Amount must be a positive number",
			},
		}
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(errorResponse)
		return
	}

	// Initialize payment data using tokenized card information
	card := paymentmethods.NewCreditCardDataWithToken(paymentToken)

	// Create billing address for AVS verification
	address := base.NewAddress(sanitizePostalCode(billingZip))

	// Configure the payment transaction using the provided amount
	amountStr = strconv.FormatFloat(amount, 'f', 2, 64)
	val, _ := stringutils.ToDecimalAmount(amountStr)
	transaction := card.ChargeWithAmount(val)
	transaction.WithAllowDuplicates(true)
	transaction.WithCurrency("USD")
	transaction.WithAddress(address)

	ctx := context.Background()
	response, err := api.ExecuteGateway[transactions.Transaction](ctx, transaction)
	if err != nil {
		w.Header().Set("Content-Type", "application/json")
		errorResponse := Response{
			Success: false,
			Message: "Internal server error",
			Error: &ErrorInfo{
				Code:    "SERVER_ERROR",
				Details: err.Error(),
			},
		}
		w.WriteHeader(http.StatusInternalServerError)
		json.NewEncoder(w).Encode(errorResponse)
		return
	}

	// Check for successful response code
	if response.GetResponseCode() != "00" {
		w.Header().Set("Content-Type", "application/json")
		errorResponse := Response{
			Success: false,
			Message: "Payment processing failed",
			Error: &ErrorInfo{
				Code:    "PAYMENT_DECLINED",
				Details: response.GetResponseMessage(),
			},
		}
		w.WriteHeader(http.StatusBadRequest)
		json.NewEncoder(w).Encode(errorResponse)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	successResponse := Response{
		Success: true,
		Message: "Payment successful! Transaction ID: " + response.GetTransactionId(),
		Data: map[string]string{
			"transactionId": response.GetTransactionId(),
		},
	}
	json.NewEncoder(w).Encode(successResponse)
}

func main() {
	// Initialize environment configuration
	err := godotenv.Load()
	if err != nil {
		log.Fatal("Error loading .env file")
	}

	// Configure Global Payments SDK with credentials and settings
	config := serviceconfigs.NewPorticoConfig()
	config.SecretApiKey = os.Getenv("SECRET_API_KEY")
	config.DeveloperId = "000000"
	config.VersionNumber = "0000"
	config.ServiceUrl = "https://cert.api2.heartlandportico.com"

	err = api.ConfigureService(config, "default")
	if err != nil {
		log.Fatal("Error configuring Global Payments service:", err)
	}

	// Set up routes
	http.Handle("/", http.FileServer(http.Dir("static")))
	http.Handle("/config", http.HandlerFunc(handleConfig))
	http.Handle("/process-payment", http.HandlerFunc(handlePayment))

	// Get port from environment variable or use default
	port := os.Getenv("PORT")
	if port == "" {
		port = "8000"
	}

	log.Printf("Server starting on http://localhost:%s", port)
	log.Printf("Server also accessible at http://127.0.0.1:%s", port)
	log.Fatal(http.ListenAndServe("0.0.0.0:"+port, nil))
}
