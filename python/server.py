"""
Global Payments SDK Template - Python Flask

This Flask application provides a starting template for Global Payments SDK integration.
Customize the endpoints and logic below for your specific use case.
"""

import os
import re
from flask import Flask, request, jsonify
from dotenv import load_dotenv
from globalpayments.api import PorticoConfig, ServicesContainer
from globalpayments.api.payment_methods import CreditCardData
from globalpayments.api.entities import Address
from globalpayments.api.entities.exceptions import ApiException

# Load environment variables
load_dotenv()

# Initialize application
app = Flask(__name__, static_folder='.')

def configure_sdk():
    """
    Configure the Global Payments SDK with necessary credentials and settings.
    Customize these settings for your environment.
    """
    config = PorticoConfig()
    config.secret_api_key = os.getenv('SECRET_API_KEY')
    config.service_url = 'https://cert.api2.heartlandportico.com'  # Use production URL for live transactions
    config.developer_id = '000000'  # Your developer ID
    config.version_number = '0000'  # Your version number
    
    ServicesContainer.configure(config)

# Configure SDK on startup
configure_sdk()

def sanitize_postal_code(postal_code: str) -> str:
    """
    Utility function to sanitize postal code.
    Customize validation logic as needed for your use case.
    """
    sanitized = re.sub(r'[^a-zA-Z0-9-]', '', postal_code or '')
    return sanitized[:10]

@app.route('/')
def index():
    """Serve the main HTML page."""
    return app.send_static_file('index.html')

@app.route('/config')
def get_config():
    """
    Config endpoint - provides public API key for client-side use.
    Customize response data as needed.
    """
    return jsonify({
        'success': True,
        'data': {
            'publicApiKey': os.getenv('PUBLIC_API_KEY')
            # Add other configuration data as needed
        }
    })

@app.route('/process-payment', methods=['POST'])
def process_payment():
    """
    Example payment processing endpoint.
    Customize this endpoint for your specific payment flow.
    """
    try:
        # TODO: Add your payment processing logic here
        # Example implementation for basic charge:
        
        if 'payment_token' not in request.form:
            raise ApiException('Payment token is required')

        card = CreditCardData()
        card.token = request.form['payment_token']

        # Customize amount and other parameters as needed
        amount = float(request.form.get('amount', 10.00))

        # Add billing address if needed
        if 'billing_zip' in request.form:
            address = Address()
            address.postal_code = sanitize_postal_code(request.form['billing_zip'])
            
            response = card.charge(amount)\
                .with_allow_duplicates(True)\
                .with_currency('USD')\
                .with_address(address)\
                .execute()
        else:
            # Process without address
            response = card.charge(amount)\
                .with_allow_duplicates(True)\
                .with_currency('USD')\
                .execute()

        return jsonify({
            'success': True,
            'message': 'Payment processed successfully',
            'data': {'transactionId': response.transaction_id}
        })

    except ApiException as e:
        return jsonify({
            'success': False,
            'message': 'Payment processing failed',
            'error': str(e)
        }), 400
    except Exception as e:
        return jsonify({
            'success': False,
            'message': 'Payment processing failed',
            'error': str(e)
        }), 500

# Add your custom endpoints here
# Examples:
# @app.route('/authorize', methods=['POST'])
# def authorize_payment():
#     # Authorization only logic
#     pass
#
# @app.route('/capture', methods=['POST'])  
# def capture_payment():
#     # Capture authorized payment logic
#     pass
#
# @app.route('/refund', methods=['POST'])
# def refund_payment():
#     # Process refund logic
#     pass
#
# @app.route('/transaction/<transaction_id>')
# def get_transaction(transaction_id):
#     # Get transaction details logic
#     pass

# Start the server if this file is run directly
if __name__ == '__main__':
    port = int(os.getenv('PORT', 8000))
    print(f"Server running at http://localhost:{port}")
    print("Customize this template for your use case!")
    app.run(host='0.0.0.0', port=port, debug=True)