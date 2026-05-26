/**
 * GP-API 3DS2 Backend — Node.js / Express
 *
 * Server-side Global Payments calls are made through the official Node SDK.
 * Hosted Fields still runs in the browser and returns a single-use payment token.
 */

import express from 'express';
import crypto from 'node:crypto';
import * as dotenv from 'dotenv';
import {
  GP_VERSION,
  charge,
  checkEnrollment,
  getAuthenticationData,
  hostedFieldsToken,
  initiateAuthentication,
  mapAuthentication,
  mapEnrollment,
  mapTransaction,
  rawSecure,
  rawTransaction,
} from './gp-sdk.js';

dotenv.config();

const app = express();
const PORT = process.env.PORT || 3001;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', process.env.FRONTEND_ORIGIN || 'http://localhost:8000');
  res.setHeader('Vary', 'Origin');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', backend: 'nodejs', version: '1.0.0', sdk: 'globalpayments-api' });
});

app.all('/3ds-method-notification', (req, res) => {
  sendNotificationPage(res, 'handleMethodNotification', req.body?.threeDSMethodData || req.query?.threeDSMethodData || '');
});

app.all('/3ds-challenge-notification', (req, res) => {
  sendNotificationPage(res, 'handleChallengeNotification', req.body?.cres || req.body?.CRes || req.query?.cres || req.query?.CRes || '');
});

app.get('/api/tokenization-config', async (_req, res) => {
  try {
    const token = await hostedFieldsToken();
    res.json({
      success: true,
      data: {
        env: process.env.GP_API_ENVIRONMENT || 'sandbox',
        accessToken: token.accessToken,
        accountName: token.accountName,
        merchantId: token.merchantId,
        apiVersion: GP_VERSION,
      },
    });
  } catch (err) {
    errorResponse(res, err);
  }
});

app.post('/api/check-enrollment', async (req, res) => {
  try {
    const secure = await checkEnrollment({
      ...req.body,
      amount: req.body?.order?.amount || req.body?.amount || '10.00',
      currency: req.body?.order?.currency || req.body?.currency || 'GBP',
    });
    res.json({ success: true, data: mapEnrollment(secure), raw: rawSecure(secure) });
  } catch (err) {
    errorResponse(res, err);
  }
});

app.post('/api/initiate-auth', async (req, res) => {
  try {
    const secure = await initiateAuthentication({
      ...req.body,
      amount: req.body?.order?.amount || req.body?.amount || '10.00',
      currency: req.body?.order?.currency || req.body?.currency || 'GBP',
    });
    res.json({ success: true, data: mapAuthentication(secure), raw: rawSecure(secure) });
  } catch (err) {
    errorResponse(res, err);
  }
});

app.post('/api/get-auth-result', async (req, res) => {
  try {
    const serverTransId = String(req.body?.server_trans_id || '').trim();
    if (!serverTransId) {
      return res.status(400).json({ success: false, error: 'server_trans_id is required' });
    }
    const secure = await getAuthenticationData(serverTransId, req.body?.amount || '10.00');
    res.json({ success: true, data: mapAuthentication(secure), raw: rawSecure(secure) });
  } catch (err) {
    errorResponse(res, err);
  }
});

app.post('/api/authorize-payment', async (req, res) => {
  try {
    const authenticationId = req.body?.authentication_id || req.body?.three_ds?.authentication_id || req.body?.three_ds?.server_trans_ref;
    const secure = authenticationId
      ? await getAuthenticationData(authenticationId, req.body?.amount || '10.00')
      : null;
    const transaction = await charge(req.body, secure);
    res.json({ success: true, data: mapTransaction(transaction), raw: rawTransaction(transaction) });
  } catch (err) {
    errorResponse(res, err);
  }
});

function errorResponse(res, err) {
  const status = err?.responseCode && Number.isInteger(Number(err.responseCode)) && Number(err.responseCode) >= 400 && Number(err.responseCode) <= 599
    ? Number(err.responseCode)
    : 500;
  res.status(status).json({
    success: false,
    error: err?.message || 'Global Payments SDK error',
    raw: rawError(err),
  });
}

function rawError(err) {
  if (!err) return {};
  return {
    name: err.name,
    message: err.message,
    responseCode: err.responseCode,
    responseMessage: err.responseMessage,
    stack: process.env.NODE_ENV === 'production' ? undefined : err.stack,
  };
}

function sendNotificationPage(res, handler, data) {
  const nonce = crypto.randomBytes(16).toString('base64');
  res.setHeader('Content-Security-Policy', `default-src 'none'; script-src 'nonce-${nonce}' https://cdn.jsdelivr.net; base-uri 'none'; frame-ancestors 'self'`);
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'no-referrer');
  res.type('html').send(notificationPage(handler, data, nonce));
}

function notificationPage(handler, data, nonce) {
  const targetOrigin = process.env.FRONTEND_ORIGIN || 'http://localhost:8000';
  const payload = encodeNotificationPayload(data);
  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>3DS Notification</title>
  <script src="https://cdn.jsdelivr.net/npm/globalpayments-3ds@1.8.7/dist/globalpayments-3ds.min.js"></script>
</head>
<body>
<script nonce="${nonce}">
  const notificationData = atob(${JSON.stringify(payload)});
  window.GlobalPayments?.ThreeDSecure?.${handler}(notificationData, ${JSON.stringify(targetOrigin)});
</script>
</body>
</html>`;
}

function encodeNotificationPayload(data) {
  const value = String(data || '');
  if (value.length > 12000 || !/^[A-Za-z0-9+/=_-]*$/.test(value)) {
    return '';
  }
  return Buffer.from(value, 'utf8').toString('base64');
}

app.listen(PORT, '0.0.0.0', () => {
  console.log(`Global Payments SDK 3DS2 backend (Node.js) listening on http://0.0.0.0:${PORT}`);
});
