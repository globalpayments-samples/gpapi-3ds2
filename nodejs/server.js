/**
 * GP-API 3DS2 Backend — Node.js / Express
 *
 * All GP-API calls use direct fetch() to avoid Node.js SDK gaps in 3DS2 support.
 * Token caching is handled by auth.js.
 */

import express from 'express';
import * as dotenv from 'dotenv';
import { randomUUID } from 'crypto';
import { getAccessToken, GP_API_BASE, GP_VERSION } from './auth.js';

dotenv.config();

const app  = express();
const PORT = process.env.PORT || 3001;

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// CORS — allow the frontend (any origin in dev)
app.use((req, res, next) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function gpRequest(method, path, body) {
  const token = await getAccessToken();
  const url   = `${GP_API_BASE}${path}`;

  const opts = {
    method,
    headers: {
      'Content-Type':  'application/json',
      'X-GP-Version':  GP_VERSION,
      'Authorization': `Bearer ${token}`,
    },
  };
  if (body) opts.body = JSON.stringify(body);

  const resp = await fetch(url, opts);
  const data = await resp.json();

  if (!resp.ok) {
    const err = new Error(data.error?.message || `GP-API error ${resp.status}`);
    err.gpData = data;
    err.status = resp.status;
    throw err;
  }
  return data;
}

/** Convert decimal amount string to minor units integer string ("10.00" → "1000") */
function toMinorUnits(amount) {
  return String(Math.round(parseFloat(amount) * 100));
}

/** Convert 4-digit year to 2-digit ("2026" → "26") */
function twoDigitYear(year) {
  return String(year).slice(-2);
}

function errorResponse(res, err) {
  const gpData = err.gpData || {};
  return res.status(err.status || 500).json({
    success: false,
    error:          err.message,
    gp_error_code:  gpData.error?.code,
    gp_error_detail: gpData.error?.detail,
    raw: gpData,
  });
}

// ─── Routes ──────────────────────────────────────────────────────────────────

/** GET /api/health */
app.get('/api/health', (_req, res) => {
  res.json({ status: 'ok', backend: 'nodejs', version: '1.0.0' });
});

/**
 * POST /api/check-enrollment
 * Step 1 — Check card availability (CHECK_AVAILABILITY / enrollment check).
 */
app.post('/api/check-enrollment', async (req, res) => {
  try {
    const { card_number, exp_month, exp_year } = req.body;

    const payload = {
      account_name: process.env.GP_ACCOUNT_NAME || 'transaction_processing',
      account_id:   process.env.GP_ACCOUNT_ID,
      merchant_id:  process.env.GP_MERCHANT_ID,
      channel:      'CNP',
      country:      'GB',
      amount:       '1000',
      currency:     'GBP',
      reference:    randomUUID(),
      payment_method: {
        entry_mode: 'ECOM',
        card: {
          number:       card_number,
          expiry_month: exp_month,
          expiry_year:  twoDigitYear(exp_year),
        },
      },
      three_ds: {
        source:          'BROWSER',
        preference:      'NO_PREFERENCE',
        message_version: '2.2.0',
      },
      notifications: {
        challenge_return_url:    process.env.CHALLENGE_NOTIFICATION_URL,
        three_ds_method_return_url: process.env.METHOD_NOTIFICATION_URL,
      },
    };

    const raw = await gpRequest('POST', '/authentications', payload);

    // Build method_data for the device-fingerprint iframe
    let method_data = null;
    if (raw.three_ds?.acs_info?.method_url) {
      const methodJson = JSON.stringify({
        threeDSServerTransID: raw.id,
        methodNotificationURL: process.env.METHOD_NOTIFICATION_URL,
      });
      method_data = Buffer.from(methodJson).toString('base64');
    }

    res.json({
      success: true,
      data: {
        server_trans_id: raw.id,
        enrolled:        raw.three_ds?.enrolled,
        method_url:      raw.three_ds?.acs_info?.method_url || null,
        method_data,
      },
      raw,
    });
  } catch (err) {
    errorResponse(res, err);
  }
});

/**
 * POST /api/initiate-auth
 * Step 3 — Initiate authentication (passes browser data + payer info).
 */
app.post('/api/initiate-auth', async (req, res) => {
  try {
    const {
      server_trans_id,
      method_url_completion,
      card_number,
      exp_month,
      exp_year,
      cardholder_name,
      browser_data,
      order,
    } = req.body;

    const payload = {
      account_name: process.env.GP_ACCOUNT_NAME || 'transaction_processing',
      account_id:   process.env.GP_ACCOUNT_ID,
      merchant_id:  process.env.GP_MERCHANT_ID,
      channel:      'CNP',
      country:      'GB',
      amount:       toMinorUnits(order?.amount || '10.00'),
      currency:     order?.currency || 'GBP',
      reference:    randomUUID(),
      payment_method: {
        entry_mode: 'ECOM',
        card: {
          number:       card_number,
          expiry_month: exp_month,
          expiry_year:  twoDigitYear(exp_year),
          full_name:    cardholder_name || 'Test User',
        },
      },
      three_ds: {
        source:               'BROWSER',
        preference:           'NO_PREFERENCE',
        message_version:      '2.2.0',
        server_trans_ref:     server_trans_id,
        method_url_completion: method_url_completion || 'UNAVAILABLE',
      },
      order: {
        amount:            toMinorUnits(order?.amount || '10.00'),
        currency:          order?.currency || 'GBP',
        reference:         randomUUID(),
        address_indicator: false,
        date_time_created: new Date().toISOString(),
      },
      payer: {
        email: 'test@example.com',
        billing_address: {
          line1:       '1 Test Street',
          city:        'London',
          postal_code: 'SW1A 1AA',
          country:     '826',
        },
      },
      browser_data: {
        accept_header:        browser_data?.accept_header    || 'text/html,application/xhtml+xml',
        color_depth:          String(browser_data?.color_depth    || '24'),
        ip:                   browser_data?.ip               || '123.123.123.123',
        java_enabled:         String(browser_data?.java_enabled   ?? 'false'),
        javascript_enabled:   String(browser_data?.javascript_enabled ?? 'true'),
        language:             browser_data?.language         || 'en-GB',
        screen_height:        String(browser_data?.screen_height  || '1080'),
        screen_width:         String(browser_data?.screen_width   || '1920'),
        challenge_window_size: browser_data?.challenge_window_size || 'FULL_SCREEN',
        timezone:             String(browser_data?.timezone       || '0'),
        user_agent:           browser_data?.user_agent       || 'Mozilla/5.0',
      },
      notifications: {
        challenge_return_url: process.env.CHALLENGE_NOTIFICATION_URL,
      },
    };

    const raw = await gpRequest('POST', '/authentications', payload);

    res.json({
      success: true,
      data: {
        server_trans_id:      raw.id,
        status:               raw.status,
        acs_reference_number: raw.three_ds?.acs_reference_number,
        acs_trans_id:         raw.three_ds?.acs_trans_id,
        acs_signed_content:   raw.three_ds?.acs_signed_content,
        acs_challenge_url:    raw.three_ds?.acs_challenge_url || raw.three_ds?.challenge_value,
      },
      raw,
    });
  } catch (err) {
    errorResponse(res, err);
  }
});

/**
 * POST /api/get-auth-result
 * Step 5 — Retrieve final authentication result.
 */
app.post('/api/get-auth-result', async (req, res) => {
  try {
    const { server_trans_id } = req.body;
    if (!server_trans_id) {
      return res.status(400).json({ success: false, error: 'server_trans_id is required' });
    }

    const raw = await gpRequest('GET', `/authentications/${server_trans_id}`);

    res.json({
      success: true,
      data: {
        status:               raw.status,
        eci:                  raw.three_ds?.eci,
        authentication_value: raw.three_ds?.authentication_value,
        ds_trans_ref:         raw.three_ds?.ds_trans_ref,
        message_version:      raw.three_ds?.message_version,
        server_trans_ref:     raw.three_ds?.server_trans_ref || raw.id,
      },
      raw,
    });
  } catch (err) {
    errorResponse(res, err);
  }
});

/**
 * POST /api/authorize-payment
 * Step 6 — Authorize (SALE) with 3DS authentication data.
 */
app.post('/api/authorize-payment', async (req, res) => {
  try {
    const { card_number, exp_month, exp_year, cvn, amount, currency, three_ds } = req.body;

    const payload = {
      account_name: process.env.GP_ACCOUNT_NAME || 'transaction_processing',
      account_id:   process.env.GP_ACCOUNT_ID,
      merchant_id:  process.env.GP_MERCHANT_ID,
      channel:      'CNP',
      type:         'SALE',
      amount:       toMinorUnits(amount || '10.00'),
      currency:     currency || 'GBP',
      reference:    randomUUID(),
      country:      'GB',
      payment_method: {
        entry_mode: 'ECOM',
        card: {
          number:       card_number,
          expiry_month: exp_month,
          expiry_year:  twoDigitYear(exp_year),
          cvv:          cvn,
        },
      },
      three_ds: {
        source:               'BROWSER',
        authentication_value: three_ds?.authentication_value,
        server_trans_ref:     three_ds?.server_trans_ref,
        ds_trans_ref:         three_ds?.ds_trans_ref,
        eci:                  three_ds?.eci,
        message_version:      three_ds?.message_version || '2.2.0',
      },
    };

    const raw = await gpRequest('POST', '/transactions', payload);

    res.json({
      success: true,
      data: {
        transaction_id: raw.id,
        status:         raw.status,
        result_code:    raw.action?.result_code,
        amount:         raw.amount,
        currency:       raw.currency,
      },
      raw,
    });
  } catch (err) {
    errorResponse(res, err);
  }
});

// ─── Start ────────────────────────────────────────────────────────────────────

app.listen(PORT, '0.0.0.0', () => {
  console.log(`GP-API 3DS2 backend (Node.js) listening on http://0.0.0.0:${PORT}`);
});
