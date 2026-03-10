/**
 * GP-API Token Cache
 *
 * Manages OAuth2 bearer token generation and caching.
 * Regenerates automatically when within 60 seconds of expiry.
 */

import crypto from 'crypto';

const GP_API_BASE = 'https://apis.sandbox.globalpay.com/ucp';
const GP_VERSION  = '2021-03-22';

let cachedToken = null;
let tokenExpiresAt = 0;

/**
 * Generate a new bearer token from GP-API /accesstoken.
 */
async function generateToken() {
  const appId  = process.env.GP_APP_ID;
  const appKey = process.env.GP_APP_KEY;

  if (!appId || !appKey) {
    throw new Error('GP_APP_ID and GP_APP_KEY must be set in environment');
  }

  const nonce  = Date.now().toString();
  const secret = crypto.createHash('sha512').update(`${nonce}.${appKey}`).digest('hex');

  const response = await fetch(`${GP_API_BASE}/accesstoken`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-GP-Version': GP_VERSION,
    },
    body: JSON.stringify({
      app_id:      appId,
      nonce,
      secret,
      grant_type: 'client_credentials',
    }),
  });

  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(`Token generation failed (${response.status}): ${JSON.stringify(err)}`);
  }

  const data = await response.json();

  // data.token already includes the value; type is "Bearer"
  cachedToken    = data.token;
  tokenExpiresAt = Date.now() + (data.seconds_to_expire - 60) * 1000;

  console.log(`[auth] New token obtained, expires in ${data.seconds_to_expire}s`);
  return cachedToken;
}

/**
 * Returns a valid bearer token, regenerating if needed.
 * @returns {Promise<string>} raw token value (without "Bearer " prefix)
 */
export async function getAccessToken() {
  if (cachedToken && Date.now() < tokenExpiresAt) {
    return cachedToken;
  }
  return generateToken();
}

export { GP_API_BASE, GP_VERSION };
