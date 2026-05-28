import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const gp = require('globalpayments-api');

export const GP_VERSION = '2021-03-22';
export const CONFIG_NAME = 'gpapi-3ds-sample';

const {
  AccessTokenInfo,
  Address,
  AddressType,
  AuthenticationSource,
  BrowserData,
  Channel,
  ChallengeWindowSize,
  ColorDepth,
  CreditCardData,
  Environment,
  GpApiConfig,
  GpApiService,
  IntervalToExpire,
  MethodUrlCompletion,
  Secure3dService,
  ServicesContainer,
  ThreeDSecure,
} = gp;

let configured = false;

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} must be set in environment`);
  return value;
}

function env(name, fallback = undefined) {
  return process.env[name] || fallback;
}

function environment() {
  return (env('GP_API_ENVIRONMENT', 'sandbox').toLowerCase() === 'production')
    ? Environment.Production
    : Environment.Test;
}

function accessTokenInfo() {
  const info = new AccessTokenInfo();
  info.transactionProcessingAccountName = env('GP_ACCOUNT_NAME', 'transaction_processing');
  info.transactionProcessingAccountID = env('GP_ACCOUNT_ID');
  info.tokenizationAccountName = env('GP_TOKENIZATION_ACCOUNT_NAME');
  info.merchantId = env('GP_PARTNER_MERCHANT_ID');
  return info;
}

export function sdkConfig({ tokenization = false } = {}) {
  const config = new GpApiConfig();
  config.appId = required('GP_APP_ID');
  config.appKey = required('GP_APP_KEY');
  config.environment = environment();
  config.channel = Channel.CardNotPresent;
  config.country = env('GP_COUNTRY', 'GB');
  config.merchantId = env('GP_PARTNER_MERCHANT_ID');
  config.accessTokenInfo = accessTokenInfo();
  config.methodNotificationUrl = env('METHOD_NOTIFICATION_URL');
  config.challengeNotificationUrl = env('CHALLENGE_NOTIFICATION_URL');
  config.merchantContactUrl = env('GP_MERCHANT_CONTACT_URL', 'https://developer.globalpay.com/');

  if (tokenization) {
    config.permissions = ['PMT_POST_Create_Single'];
    config.intervalToExpire = IntervalToExpire.TEN_MINUTES;
  }

  return config;
}

export function ensureConfigured() {
  if (configured) return;
  ServicesContainer.configureService(sdkConfig(), CONFIG_NAME);
  configured = true;
}

export function resetSdkConfig() {
  ServicesContainer.removeConfiguration(CONFIG_NAME);
  configured = false;
}

export async function hostedFieldsToken() {
  const info = await GpApiService.generateTransactionKey(sdkConfig({ tokenization: true }));
  return {
    accessToken: info.accessToken,
    accountName: env('GP_TOKENIZATION_ACCOUNT_NAME', info.tokenizationAccountName || env('GP_ACCOUNT_NAME', 'transaction_processing')),
    merchantId: info.merchantId,
  };
}

export function paymentMethodFrom(input) {
  const card = new CreditCardData();
  if (input.payment_method_id) {
    card.token = input.payment_method_id;
  } else {
    card.number = input.card_number;
    card.expMonth = input.exp_month;
    card.expYear = input.exp_year;
    card.cvn = input.cvn;
  }
  card.cardHolderName = input.cardholder_name || 'Test User';
  return card;
}

export function browserDataFrom(input = {}) {
  const browserData = new BrowserData();
  browserData.acceptHeader = input.accept_header || 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
  browserData.colorDepth = normalizeColorDepth(input.color_depth);
  browserData.ipAddress = input.ip || '127.0.0.1';
  browserData.javaEnabled = booleanValue(input.java_enabled, false);
  browserData.javaScriptEnabled = booleanValue(input.javascript_enabled, true);
  browserData.language = input.language || 'en-GB';
  browserData.screenHeight = numberValue(input.screen_height, 1080);
  browserData.screenWidth = numberValue(input.screen_width, 1920);
  browserData.challengWindowSize = normalizeChallengeWindow(input.challenge_window_size);
  browserData.timeZone = String(input.timezone ?? '0');
  browserData.userAgent = input.user_agent || 'Mozilla/5.0';
  return browserData;
}

export function shippingAddress() {
  const address = new Address();
  address.streetAddress1 = '1 Test Street';
  address.city = 'London';
  address.postalCode = 'SW1A 1AA';
  address.countryCode = '826';
  return address;
}

export function authSeed(serverTransactionId) {
  const threeDSecure = new ThreeDSecure();
  threeDSecure.serverTransactionId = serverTransactionId;
  return threeDSecure;
}

export function statusOf(secure) {
  return secure?.status || secure?.enrolled || null;
}

export function mapEnrollment(secure) {
  return {
    server_trans_id: secure.serverTransactionId,
    enrolled: secure.enrolled,
    message_version: secure.messageVersion || secure.version,
    method_url: secure.issuerAcsUrl || null,
    method_data: secure.payerAuthenticationRequest || null,
    server_trans_ref: secure.providerServerTransRef || secure.serverTransactionId,
  };
}

export function mapAuthentication(secure) {
  const status = statusOf(secure);
  return {
    server_trans_id: secure.serverTransactionId,
    status,
    acs_reference_number: secure.acsReferenceNumber,
    acs_trans_id: secure.acsTransactionId,
    acs_signed_content: secure.acsSignedContent,
    acs_challenge_url: secure.issuerAcsUrl,
    eci: secure.eci,
    authentication_value: secure.authenticationValue,
    ds_trans_ref: secure.directoryServerTransactionId,
    message_version: secure.messageVersion,
    server_trans_ref: secure.providerServerTransRef || secure.serverTransactionId,
    challenge: status === 'CHALLENGE_REQUIRED' ? {
      requestUrl: secure.issuerAcsUrl,
      encodedChallengeRequest: secure.payerAuthenticationRequest,
      messageType: secure.messageType || 'creq',
    } : undefined,
  };
}

export function mapTransaction(transaction) {
  return {
    transaction_id: transaction.transactionId,
    status: transaction.responseMessage,
    result_code: transaction.responseCode,
    amount: transaction.balanceAmount || transaction.amount,
    currency: transaction.currency,
  };
}

export function rawSecure(secure) {
  return JSON.parse(JSON.stringify(secure, (_key, value) => {
    if (typeof value === 'bigint') return value.toString();
    return value;
  }));
}

export function rawTransaction(transaction) {
  return rawSecure(transaction);
}

export async function checkEnrollment(input) {
  ensureConfigured();
  return Secure3dService.checkEnrollment(paymentMethodFrom(input))
    .withAmount(input.amount || '10.00')
    .withCurrency(input.currency || 'GBP')
    .execute(CONFIG_NAME);
}

export async function initiateAuthentication(input) {
  ensureConfigured();
  return Secure3dService.initiateAuthentication(
    paymentMethodFrom(input),
    authSeed(input.server_trans_id),
  )
    .withAmount(input.amount || '10.00')
    .withCurrency(input.currency || 'GBP')
    .withAuthenticationSource(AuthenticationSource.Browser)
    .withMethodUrlCompletion(normalizeMethodCompletion(input.method_url_completion))
    .withOrderCreateDate(new Date().toISOString().replace('T', ' ').slice(0, 19))
    .withAddress(shippingAddress(), AddressType.Shipping)
    .withBrowserData(browserDataFrom(input.browser_data))
    .execute(CONFIG_NAME);
}

export async function getAuthenticationData(serverTransactionId, amount = '10.00') {
  ensureConfigured();
  return Secure3dService.getAuthenticationData()
    .withServerTransactionId(serverTransactionId)
    .withAmount(amount)
    .execute(CONFIG_NAME);
}

export async function charge(input, secure) {
  ensureConfigured();
  const card = paymentMethodFrom(input);
  if (secure) card.threeDSecure = secure;
  return card
    .charge(input.amount || '10.00')
    .withCurrency(input.currency || 'GBP')
    .execute(CONFIG_NAME);
}

function booleanValue(value, fallback) {
  if (typeof value === 'boolean') return value;
  if (value === 'true') return true;
  if (value === 'false') return false;
  return fallback;
}

function numberValue(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function normalizeColorDepth(value) {
  const numeric = Number(value);
  if (numeric >= 48) return ColorDepth.FortyEightBits;
  if (numeric >= 32) return ColorDepth.ThirtyTwoBits;
  if (numeric >= 24) return ColorDepth.TwentyFourBits;
  if (numeric >= 16) return ColorDepth.SixteenBits;
  if (numeric >= 15) return ColorDepth.FifteenBits;
  if (numeric >= 8) return ColorDepth.EightBits;
  if (numeric >= 4) return ColorDepth.FourBits;
  if (numeric >= 2) return ColorDepth.TwoBits;
  return ColorDepth.OneBit;
}

function normalizeChallengeWindow(value) {
  return Object.values(ChallengeWindowSize).includes(value)
    ? value
    : ChallengeWindowSize.FullScreen;
}

function normalizeMethodCompletion(value) {
  return Object.values(MethodUrlCompletion).includes(value)
    ? value
    : MethodUrlCompletion.Unavailable;
}
