const crypto = require('crypto');
const { GoogleAuth } = require('google-auth-library');

const PACKAGE_NAME = process.env.ANDROID_PACKAGE_NAME || 'com.gillingteknik.piratio';
const SERVICE_ACCOUNT_JSON = process.env.GOOGLE_SERVICE_ACCOUNT_JSON || '';
const REQUIRE_PLAY_INTEGRITY = process.env.REQUIRE_PLAY_INTEGRITY
  ? process.env.REQUIRE_PLAY_INTEGRITY === 'true'
  : process.env.NODE_ENV === 'production';

const PLAY_INTEGRITY_SCOPE = 'https://www.googleapis.com/auth/playintegrity';
const ANDROID_PUBLISHER_SCOPE = 'https://www.googleapis.com/auth/androidpublisher';

const COIN_PRODUCTS = Object.freeze({
  piratio_coins_1000: 1000,
  piratio_coins_3000: 3000,
  piratio_coins_8000: 8000,
});

let parsedCredentials;

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function serviceCredentials() {
  if (!SERVICE_ACCOUNT_JSON) return undefined;
  if (parsedCredentials) return parsedCredentials;
  try {
    parsedCredentials = JSON.parse(SERVICE_ACCOUNT_JSON);
    return parsedCredentials;
  } catch {
    throw httpError(503, 'GOOGLE_SERVICE_ACCOUNT_JSON_INVALID');
  }
}

async function accessToken(scope) {
  const options = { scopes: [scope] };
  const credentials = serviceCredentials();
  if (credentials) options.credentials = credentials;
  const auth = new GoogleAuth(options);
  try {
    const client = await auth.getClient();
    const result = await client.getAccessToken();
    const token = typeof result === 'string' ? result : result && result.token;
    if (!token) throw new Error('missing token');
    return token;
  } catch (error) {
    console.error('Google service authentication failed:', error.message);
    throw httpError(503, 'GOOGLE_SERVICE_AUTH_UNAVAILABLE');
  }
}

function requestHash(binding) {
  return crypto.createHash('sha256').update(String(binding)).digest('base64url');
}

function purchaseLedgerKey(purchaseToken) {
  return 'google-purchase:' + crypto.createHash('sha256').update(String(purchaseToken)).digest('hex');
}

function configured() {
  return Boolean(SERVICE_ACCOUNT_JSON || process.env.GOOGLE_APPLICATION_CREDENTIALS);
}

async function verifyIntegrity(integrityToken, binding) {
  if (!integrityToken) {
    if (REQUIRE_PLAY_INTEGRITY) throw httpError(401, 'PLAY_INTEGRITY_REQUIRED');
    return { enforced: false, reason: 'token-not-supplied' };
  }
  if (!configured()) {
    if (REQUIRE_PLAY_INTEGRITY) throw httpError(503, 'PLAY_INTEGRITY_SERVER_NOT_CONFIGURED');
    return { enforced: false, reason: 'service-account-not-configured' };
  }

  const token = await accessToken(PLAY_INTEGRITY_SCOPE);
  const response = await fetch(
    `https://playintegrity.googleapis.com/v1/${encodeURIComponent(PACKAGE_NAME)}:decodeIntegrityToken`,
    {
      method: 'POST',
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': 'application/json',
      },
      body: JSON.stringify({ integrity_token: String(integrityToken) }),
    }
  );
  const decodedResponse = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error('Play Integrity decode failed:', response.status, decodedResponse.error || decodedResponse);
    throw httpError(401, 'PLAY_INTEGRITY_REJECTED');
  }

  const payload = decodedResponse.tokenPayloadExternal || {};
  const expectedHash = requestHash(binding);
  const actualHash = payload.requestDetails && payload.requestDetails.requestHash;
  if (!actualHash || actualHash !== expectedHash) throw httpError(401, 'PLAY_INTEGRITY_REQUEST_MISMATCH');

  const appVerdict = payload.appIntegrity && payload.appIntegrity.appRecognitionVerdict;
  const deviceVerdicts = (payload.deviceIntegrity && payload.deviceIntegrity.deviceRecognitionVerdict) || [];
  const licensingVerdict = payload.accountDetails && payload.accountDetails.appLicensingVerdict;

  if (REQUIRE_PLAY_INTEGRITY) {
    if (appVerdict !== 'PLAY_RECOGNIZED') throw httpError(401, 'PLAY_INTEGRITY_APP_NOT_RECOGNIZED');
    if (!deviceVerdicts.includes('MEETS_DEVICE_INTEGRITY') && !deviceVerdicts.includes('MEETS_STRONG_INTEGRITY')) {
      throw httpError(401, 'PLAY_INTEGRITY_DEVICE_REJECTED');
    }
    if (licensingVerdict !== 'LICENSED') throw httpError(401, 'PLAY_INTEGRITY_UNLICENSED');
  }

  return { enforced: REQUIRE_PLAY_INTEGRITY, appVerdict, deviceVerdicts, licensingVerdict };
}

async function verifyCoinPurchase(accountId, productId, purchaseToken) {
  const coins = COIN_PRODUCTS[productId];
  if (!coins) throw httpError(400, 'UNKNOWN_COIN_PRODUCT');
  if (!purchaseToken || String(purchaseToken).length < 16) throw httpError(400, 'INVALID_PURCHASE_TOKEN');
  if (!configured()) throw httpError(503, 'GOOGLE_PLAY_DEVELOPER_API_NOT_CONFIGURED');

  const token = await accessToken(ANDROID_PUBLISHER_SCOPE);
  const response = await fetch(
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/productsv2/tokens/${encodeURIComponent(String(purchaseToken))}`,
    { headers: { authorization: `Bearer ${token}`, accept: 'application/json' } }
  );
  const purchase = await response.json().catch(() => ({}));
  if (!response.ok) {
    console.error('Google purchase lookup failed:', response.status, purchase.error || purchase);
    throw httpError(401, 'GOOGLE_PURCHASE_NOT_VERIFIED');
  }

  const purchaseState = purchase.purchaseStateContext && purchase.purchaseStateContext.purchaseState;
  if (purchaseState === 'PENDING') throw httpError(409, 'GOOGLE_PURCHASE_PENDING');
  if (purchaseState !== 'PURCHASED') throw httpError(409, 'GOOGLE_PURCHASE_NOT_ACTIVE');

  const lineItems = Array.isArray(purchase.productLineItem) ? purchase.productLineItem : [];
  const lineItem = lineItems.find((item) => item.productId === productId);
  if (!lineItem) throw httpError(401, 'GOOGLE_PURCHASE_PRODUCT_MISMATCH');

  if (!purchase.obfuscatedExternalAccountId || purchase.obfuscatedExternalAccountId !== accountId) {
    throw httpError(401, 'GOOGLE_PURCHASE_ACCOUNT_MISMATCH');
  }

  const offer = lineItem.productOfferDetails || {};
  const consumed = offer.consumptionState === 'CONSUMPTION_STATE_CONSUMED';
  return { coins, consumed, orderId: purchase.orderId || '', productId };
}

async function consumeCoinPurchase(productId, purchaseToken) {
  if (!configured()) throw httpError(503, 'GOOGLE_PLAY_DEVELOPER_API_NOT_CONFIGURED');
  const token = await accessToken(ANDROID_PUBLISHER_SCOPE);
  const response = await fetch(
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(PACKAGE_NAME)}/purchases/products/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(String(purchaseToken))}:consume`,
    {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: '{}',
    }
  );
  if (!response.ok) {
    const body = await response.text();
    console.error('Google purchase consume failed:', response.status, body.slice(0, 500));
    throw httpError(502, 'GOOGLE_PURCHASE_CONSUME_FAILED');
  }
}

module.exports = {
  COIN_PRODUCTS,
  configured,
  purchaseLedgerKey,
  verifyIntegrity,
  verifyCoinPurchase,
  consumeCoinPurchase,
};
