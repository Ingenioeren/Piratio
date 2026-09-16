const http = require('http');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');
const { Pool } = require('pg');
const googlePlay = require('./googlePlaySecurity');

const PORT = Number(process.env.PORT || 8080);
const HUMAN_CAP = 20;
const PERSISTENT_BOTS = 100;
const ROOM_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const IS_PRODUCTION = process.env.NODE_ENV === 'production';
const SESSION_SECRET = process.env.SESSION_SECRET || (IS_PRODUCTION ? '' : crypto.randomBytes(32).toString('hex'));
const DATABASE_URL = process.env.DATABASE_URL || '';
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID || '';
const GOOGLE_CLIENT_SECRET = process.env.GOOGLE_CLIENT_SECRET || '';
const GOOGLE_PLAY_GAMES_APP_ID = process.env.GOOGLE_PLAY_GAMES_APP_ID || '';
const GOOGLE_REDIRECT_URI = process.env.GOOGLE_REDIRECT_URI || '';
const APPLE_BUNDLE_ID = process.env.APPLE_BUNDLE_ID || 'com.gillingteknik.piratio';
const SKIN_COSTS = [0, 250, 600, 900];

if (IS_PRODUCTION && !SESSION_SECRET) throw new Error('SESSION_SECRET is required in production');
if (IS_PRODUCTION && !DATABASE_URL) throw new Error('DATABASE_URL is required in production');

const pool = DATABASE_URL ? new Pool({
  connectionString: DATABASE_URL,
  ssl: process.env.PGSSL === 'disable' ? false : { rejectUnauthorized: false },
}) : null;
const memoryProfiles = new Map();
const memoryLedger = [];
const rooms = new Map();
const rateBuckets = new Map();
let nextClientId = 1;

async function initStore() {
  if (!pool) return;
  await pool.query(`
    CREATE TABLE IF NOT EXISTS piratio_profiles (
      account_id TEXT PRIMARY KEY,
      provider TEXT NOT NULL,
      captain_name TEXT NOT NULL DEFAULT 'Captain',
      coins INTEGER NOT NULL DEFAULT 0 CHECK (coins >= 0),
      skins INTEGER NOT NULL DEFAULT 1,
      selected_skin INTEGER NOT NULL DEFAULT 0,
      music_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      sfx_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      updated_at BIGINT NOT NULL
    );

    CREATE TABLE IF NOT EXISTS piratio_wallet_ledger (
      id BIGSERIAL PRIMARY KEY,
      account_id TEXT NOT NULL,
      delta INTEGER NOT NULL,
      reason TEXT NOT NULL,
      idempotency_key TEXT UNIQUE NOT NULL,
      created_at BIGINT NOT NULL
    );

    CREATE INDEX IF NOT EXISTS piratio_wallet_account_idx
      ON piratio_wallet_ledger(account_id, created_at DESC);
  `);
}

function defaultProfile(accountId, provider) {
  return {
    accountId,
    provider,
    captainName: 'Captain',
    coins: 0,
    skins: 1,
    selectedSkin: 0,
    musicEnabled: true,
    sfxEnabled: true,
    updatedAt: Date.now(),
  };
}

async function loadProfile(accountId, provider) {
  if (!pool) {
    if (!memoryProfiles.has(accountId)) memoryProfiles.set(accountId, defaultProfile(accountId, provider));
    return { ...memoryProfiles.get(accountId) };
  }
  const result = await pool.query('SELECT * FROM piratio_profiles WHERE account_id=$1', [accountId]);
  if (!result.rowCount) {
    const fresh = defaultProfile(accountId, provider);
    await saveProfile(fresh);
    return fresh;
  }
  const row = result.rows[0];
  return {
    accountId: row.account_id,
    provider: row.provider,
    captainName: row.captain_name,
    coins: Number(row.coins),
    skins: Number(row.skins),
    selectedSkin: Number(row.selected_skin),
    musicEnabled: row.music_enabled,
    sfxEnabled: row.sfx_enabled,
    updatedAt: Number(row.updated_at),
  };
}

async function saveProfile(profile, dbClient = null) {
  profile.updatedAt = Date.now();
  if (!pool) {
    memoryProfiles.set(profile.accountId, { ...profile });
    return profile;
  }
  const db = dbClient || pool;
  await db.query(`
    INSERT INTO piratio_profiles
      (account_id, provider, captain_name, coins, skins, selected_skin, music_enabled, sfx_enabled, updated_at)
    VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
    ON CONFLICT (account_id) DO UPDATE SET
      provider=EXCLUDED.provider,
      captain_name=EXCLUDED.captain_name,
      coins=EXCLUDED.coins,
      skins=EXCLUDED.skins,
      selected_skin=EXCLUDED.selected_skin,
      music_enabled=EXCLUDED.music_enabled,
      sfx_enabled=EXCLUDED.sfx_enabled,
      updated_at=EXCLUDED.updated_at
  `, [
    profile.accountId, profile.provider, profile.captainName, profile.coins, profile.skins,
    profile.selectedSkin, profile.musicEnabled, profile.sfxEnabled, profile.updatedAt,
  ]);
  return profile;
}

function publicProfile(profile) {
  return {
    captainName: profile.captainName,
    coins: profile.coins,
    skins: profile.skins,
    selectedSkin: profile.selectedSkin,
    musicEnabled: profile.musicEnabled,
    sfxEnabled: profile.sfxEnabled,
    updatedAt: profile.updatedAt,
  };
}

function ownsSkin(profile, skin) {
  return skin >= 0 && skin < 16 && (profile.skins & (1 << skin)) !== 0;
}

async function ledgerHasKey(idempotencyKey) {
  if (!pool) return memoryLedger.some((entry) => entry.idempotencyKey === idempotencyKey);
  const result = await pool.query('SELECT 1 FROM piratio_wallet_ledger WHERE idempotency_key=$1', [idempotencyKey]);
  return result.rowCount > 0;
}

/**
 * The only function allowed to change currency. It is deliberately not exposed as
 * a public HTTP endpoint. Match simulation and verified store purchase handlers call it.
 */
async function serverGrantCoins(accountId, provider, delta, reason, idempotencyKey) {
  if (!Number.isInteger(delta) || delta === 0 || Math.abs(delta) > 1_000_000) throw new Error('INVALID_COIN_DELTA');
  if (!idempotencyKey || idempotencyKey.length > 120) throw new Error('INVALID_IDEMPOTENCY_KEY');

  if (!pool) {
    if (memoryLedger.some((entry) => entry.idempotencyKey === idempotencyKey)) return loadProfile(accountId, provider);
    const profile = await loadProfile(accountId, provider);
    if (profile.coins + delta < 0) throw httpError(409, 'INSUFFICIENT_COINS');
    profile.coins += delta;
    memoryLedger.push({ accountId, delta, reason, idempotencyKey, createdAt: Date.now() });
    await saveProfile(profile);
    return profile;
  }

  const client = await pool.connect();
  try {
    await client.query('BEGIN');
    const duplicate = await client.query('SELECT 1 FROM piratio_wallet_ledger WHERE idempotency_key=$1', [idempotencyKey]);
    if (duplicate.rowCount) {
      await client.query('ROLLBACK');
      return loadProfile(accountId, provider);
    }
    const result = await client.query('SELECT * FROM piratio_profiles WHERE account_id=$1 FOR UPDATE', [accountId]);
    let profile = result.rowCount ? {
      accountId: result.rows[0].account_id,
      provider: result.rows[0].provider,
      captainName: result.rows[0].captain_name,
      coins: Number(result.rows[0].coins),
      skins: Number(result.rows[0].skins),
      selectedSkin: Number(result.rows[0].selected_skin),
      musicEnabled: result.rows[0].music_enabled,
      sfxEnabled: result.rows[0].sfx_enabled,
      updatedAt: Number(result.rows[0].updated_at),
    } : defaultProfile(accountId, provider);
    if (profile.coins + delta < 0) throw httpError(409, 'INSUFFICIENT_COINS');
    profile.coins += delta;
    await saveProfile(profile, client);
    await client.query(
      'INSERT INTO piratio_wallet_ledger(account_id,delta,reason,idempotency_key,created_at) VALUES($1,$2,$3,$4,$5)',
      [accountId, delta, String(reason).slice(0, 64), idempotencyKey, Date.now()]
    );
    await client.query('COMMIT');
    return profile;
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    client.release();
  }
}

async function buyOrEquipSkin(session, skin) {
  const skinIndex = clampInt(skin, 0, SKIN_COSTS.length - 1, -1);
  if (skinIndex < 0) throw httpError(400, 'INVALID_SKIN');
  let profile = await loadProfile(session.sub, session.provider);
  if (!ownsSkin(profile, skinIndex)) {
    const cost = SKIN_COSTS[skinIndex];
    profile = await serverGrantCoins(
      session.sub,
      session.provider,
      -cost,
      `skin:${skinIndex}`,
      `skin-purchase:${session.sub}:${skinIndex}`
    );
    profile.skins |= 1 << skinIndex;
  }
  profile.selectedSkin = skinIndex;
  await saveProfile(profile);
  return profile;
}

function accountId(provider, platformId) {
  return crypto.createHash('sha256').update(`${provider}:${platformId}`).digest('hex');
}

function issueSession(account, provider) {
  const payload = Buffer.from(JSON.stringify({
    sub: account,
    provider,
    iat: Date.now(),
    exp: Date.now() + 30 * 24 * 3600_000,
    jti: crypto.randomUUID(),
  })).toString('base64url');
  const signature = crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
  return `${payload}.${signature}`;
}

function readSession(req) {
  const header = String(req.headers.authorization || '');
  if (!header.startsWith('Bearer ')) return null;
  const token = header.slice(7);
  const [payload, signature] = token.split('.');
  if (!payload || !signature) return null;
  const expected = crypto.createHmac('sha256', SESSION_SECRET).update(payload).digest('base64url');
  const a = Buffer.from(signature);
  const b = Buffer.from(expected);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) return null;
  try {
    const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    if (!decoded.sub || !decoded.jti || Number(decoded.exp) < Date.now()) return null;
    return decoded;
  } catch {
    return null;
  }
}

async function verifyGoogle(serverAuthCode) {
  if (!GOOGLE_CLIENT_ID || !GOOGLE_CLIENT_SECRET) throw httpError(503, 'GOOGLE_AUTH_NOT_CONFIGURED');
  const params = new URLSearchParams({
    client_id: GOOGLE_CLIENT_ID,
    client_secret: GOOGLE_CLIENT_SECRET,
    code: String(serverAuthCode || ''),
    grant_type: 'authorization_code',
  });
  if (GOOGLE_REDIRECT_URI) params.set('redirect_uri', GOOGLE_REDIRECT_URI);

  const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: params,
  });
  const token = await tokenResponse.json();
  if (!tokenResponse.ok || !token.access_token) throw httpError(401, 'GOOGLE_CODE_REJECTED');

  let playerId = '';
  if (GOOGLE_PLAY_GAMES_APP_ID) {
    const verifyResponse = await fetch(`https://games.googleapis.com/games/v1/applications/${encodeURIComponent(GOOGLE_PLAY_GAMES_APP_ID)}/verify`, {
      headers: { authorization: `Bearer ${token.access_token}` },
    });
    const verified = await verifyResponse.json();
    if (!verifyResponse.ok) throw httpError(401, 'PLAY_GAMES_VERIFY_FAILED');
    playerId = verified.player_id || verified.playerId || '';
  }
  if (!playerId) {
    const playerResponse = await fetch('https://games.googleapis.com/games/v1/players/me', {
      headers: { authorization: `Bearer ${token.access_token}` },
    });
    const player = await playerResponse.json();
    if (!playerResponse.ok || !player.playerId) throw httpError(401, 'PLAY_GAMES_PLAYER_LOOKUP_FAILED');
    playerId = player.playerId;
  }
  return String(playerId);
}

async function verifyApple(body) {
  const playerId = String(body.playerId || body.teamPlayerID || '');
  const bundleId = String(body.bundleId || '');
  const publicKeyUrl = String(body.publicKeyUrl || '');
  const signature = String(body.signature || '');
  const salt = String(body.salt || '');
  const timestamp = Number(body.timestamp || 0);
  if (!playerId || !bundleId || !publicKeyUrl || !signature || !salt || !timestamp) throw httpError(400, 'APPLE_PAYLOAD_INCOMPLETE');
  if (bundleId !== APPLE_BUNDLE_ID) throw httpError(401, 'APPLE_BUNDLE_MISMATCH');
  if (Math.abs(Date.now() - timestamp) > 10 * 60_000) throw httpError(401, 'APPLE_SIGNATURE_EXPIRED');

  const keyUrl = new URL(publicKeyUrl);
  if (keyUrl.protocol !== 'https:' || !(keyUrl.hostname === 'apple.com' || keyUrl.hostname.endsWith('.apple.com'))) {
    throw httpError(401, 'APPLE_PUBLIC_KEY_URL_REJECTED');
  }
  const certResponse = await fetch(keyUrl);
  if (!certResponse.ok) throw httpError(401, 'APPLE_PUBLIC_KEY_UNAVAILABLE');
  const certBytes = Buffer.from(await certResponse.arrayBuffer());
  const certificate = new crypto.X509Certificate(certBytes);

  const timestampBuffer = Buffer.alloc(8);
  timestampBuffer.writeBigUInt64BE(BigInt(timestamp));
  const payload = Buffer.concat([
    Buffer.from(playerId, 'utf8'),
    Buffer.from(bundleId, 'utf8'),
    timestampBuffer,
    Buffer.from(salt, 'base64'),
  ]);
  const valid = crypto.verify('RSA-SHA256', payload, certificate.publicKey, Buffer.from(signature, 'base64'));
  if (!valid) throw httpError(401, 'APPLE_SIGNATURE_INVALID');
  return playerId;
}

function rejectAuthoritativeFields(body) {
  const forbidden = ['coins', 'skins', 'xp', 'level', 'hp', 'maxHp', 'damage', 'kills', 'rewards', 'purchaseEntitlements'];
  const attempted = forbidden.filter((field) => Object.prototype.hasOwnProperty.call(body, field));
  if (attempted.length) throw httpError(400, `AUTHORITATIVE_FIELDS_REJECTED:${attempted.join(',')}`);
}

function rateLimit(req, namespace, limit, windowMs) {
  const identity = `${namespace}:${req.socket.remoteAddress || 'unknown'}`;
  const now = Date.now();
  let bucket = rateBuckets.get(identity);
  if (!bucket || bucket.resetAt <= now) bucket = { count: 0, resetAt: now + windowMs };
  bucket.count++;
  rateBuckets.set(identity, bucket);
  if (bucket.count > limit) throw httpError(429, 'RATE_LIMITED');
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function clampInt(value, min, max, fallback) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(min, Math.min(max, Math.trunc(n)));
}

function sanitizeName(value) {
  const name = String(value || '').trim().replace(/\s+/g, ' ');
  return (name || 'Captain').slice(0, 16);
}

async function readJson(req) {
  let body = '';
  for await (const chunk of req) {
    body += chunk;
    if (body.length > 64 * 1024) throw httpError(413, 'BODY_TOO_LARGE');
  }
  if (!body) return {};
  try { return JSON.parse(body); } catch { throw httpError(400, 'BAD_JSON'); }
}

function json(res, status, payload) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
  });
  res.end(JSON.stringify(payload));
}

const server = http.createServer(async (req, res) => {
  try {
    rateLimit(req, 'http', 180, 60_000);

    if (req.method === 'GET' && req.url === '/health') {
      json(res, 200, {
        ok: true,
        rooms: rooms.size,
        humanCap: HUMAN_CAP,
        botsPerOcean: PERSISTENT_BOTS,
        database: pool ? 'postgres' : 'memory-dev',
        googleAuthConfigured: Boolean(GOOGLE_CLIENT_ID && GOOGLE_CLIENT_SECRET),
        googleServerCredentialsConfigured: googlePlay.configured(),
        appleAuthConfigured: Boolean(APPLE_BUNDLE_ID),
        durableSessionSecret: Boolean(process.env.SESSION_SECRET),
        economyAuthority: 'server',
        purchaseVerification: 'google-play-server',
        gameplayAuthority: 'server-required-for-ranked-rewards',
      });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/auth/google') {
      rateLimit(req, 'auth', 12, 60_000);
      const body = await readJson(req);
      const serverAuthCode = String(body.serverAuthCode || '');
      if (!serverAuthCode) throw httpError(400, 'GOOGLE_AUTH_CODE_REQUIRED');
      await googlePlay.verifyIntegrity(body.integrityToken, `auth-google:${serverAuthCode}`);
      const platformId = await verifyGoogle(serverAuthCode);
      const id = accountId('google_play', platformId);
      const profile = await loadProfile(id, 'google_play');
      json(res, 200, { accountId: id, token: issueSession(id, 'google_play'), profile: publicProfile(profile) });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/auth/apple') {
      rateLimit(req, 'auth', 12, 60_000);
      const body = await readJson(req);
      const platformId = await verifyApple(body);
      const id = accountId('apple_game_center', platformId);
      const profile = await loadProfile(id, 'apple_game_center');
      json(res, 200, { accountId: id, token: issueSession(id, 'apple_game_center'), profile: publicProfile(profile) });
      return;
    }

    if (req.url === '/api/profile' && (req.method === 'GET' || req.method === 'PUT')) {
      const session = readSession(req);
      if (!session) throw httpError(401, 'UNAUTHORIZED');
      let profile = await loadProfile(session.sub, session.provider);
      if (req.method === 'PUT') {
        const body = await readJson(req);
        rejectAuthoritativeFields(body);
        const requestedSkin = clampInt(body.selectedSkin, 0, 15, profile.selectedSkin);
        if (!ownsSkin(profile, requestedSkin)) throw httpError(403, 'SKIN_NOT_OWNED');
        profile = {
          ...profile,
          captainName: sanitizeName(body.captainName ?? profile.captainName),
          selectedSkin: requestedSkin,
          musicEnabled: typeof body.musicEnabled === 'boolean' ? body.musicEnabled : profile.musicEnabled,
          sfxEnabled: typeof body.sfxEnabled === 'boolean' ? body.sfxEnabled : profile.sfxEnabled,
        };
        await saveProfile(profile);
      }
      json(res, 200, { profile: publicProfile(profile) });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/shop/skin') {
      const session = readSession(req);
      if (!session) throw httpError(401, 'UNAUTHORIZED');
      const body = await readJson(req);
      rejectAuthoritativeFields(body);
      const profile = await buyOrEquipSkin(session, body.skin);
      json(res, 200, { profile: publicProfile(profile) });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/shop/google-purchase') {
      rateLimit(req, 'purchase', 30, 60_000);
      const session = readSession(req);
      if (!session) throw httpError(401, 'UNAUTHORIZED');
      if (session.provider !== 'google_play') throw httpError(400, 'GOOGLE_PURCHASE_REQUIRES_GOOGLE_ACCOUNT');
      const body = await readJson(req);
      rejectAuthoritativeFields(body);
      const productId = String(body.productId || '');
      const purchaseToken = String(body.purchaseToken || '');
      const idempotencyKey = googlePlay.purchaseLedgerKey(purchaseToken);
      const alreadyGranted = await ledgerHasKey(idempotencyKey);
      const verified = await googlePlay.verifyCoinPurchase(session.sub, productId, purchaseToken);

      if (verified.consumed) {
        if (!alreadyGranted) throw httpError(409, 'GOOGLE_PURCHASE_ALREADY_CONSUMED');
        const profile = await loadProfile(session.sub, session.provider);
        json(res, 200, { profile: publicProfile(profile), duplicate: true });
        return;
      }

      const profile = await serverGrantCoins(
        session.sub,
        session.provider,
        verified.coins,
        `google:${verified.productId}`,
        idempotencyKey
      );
      await googlePlay.consumeCoinPurchase(verified.productId, purchaseToken);
      json(res, 200, { profile: publicProfile(profile), duplicate: alreadyGranted });
      return;
    }

    json(res, 404, { error: 'NOT_FOUND' });
  } catch (error) {
    json(res, error.status || 500, { error: error.message || 'SERVER_ERROR' });
  }
});

const wss = new WebSocketServer({ server, maxPayload: 16 * 1024 });
wss.on('connection', (socket) => {
  const client = { id: `p${nextClientId++}`, name: 'Captain', roomCode: null, socket };
  send(socket, { type: 'connected', clientId: client.id, humansMax: HUMAN_CAP, bots: PERSISTENT_BOTS });

  socket.on('message', (raw) => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { send(socket, { type: 'error', code: 'BAD_JSON' }); return; }

    // Never accept game results from clients. Online gameplay will accept controls/input only.
    const forbidden = ['coins', 'xp', 'hp', 'damage', 'kills', 'level', 'reward', 'targetHp'];
    if (forbidden.some((field) => Object.prototype.hasOwnProperty.call(message, field))) {
      send(socket, { type: 'error', code: 'CLIENT_AUTHORITY_REJECTED' });
      return;
    }

    if (message.type === 'create_room') {
      leaveCurrentRoom(client);
      client.name = sanitizeName(message.name);
      const code = createRoomCode();
      const room = { code, players: new Map(), createdAt: Date.now() };
      rooms.set(code, room);
      joinRoom(client, room);
      return;
    }
    if (message.type === 'join_room') {
      leaveCurrentRoom(client);
      client.name = sanitizeName(message.name);
      const code = String(message.code || '').trim().toUpperCase();
      const room = rooms.get(code);
      if (!room) { send(socket, { type: 'join_rejected', reason: 'ROOM_NOT_FOUND', code }); return; }
      if (room.players.size >= HUMAN_CAP) { send(socket, { type: 'join_rejected', reason: 'ROOM_FULL', code }); return; }
      joinRoom(client, room);
      return;
    }
    if (message.type === 'leave_room') { leaveCurrentRoom(client); send(socket, { type: 'left_room' }); return; }
    if (message.type === 'ping') { send(socket, { type: 'pong', now: Date.now() }); return; }
    send(socket, { type: 'error', code: 'UNKNOWN_MESSAGE' });
  });
  socket.on('close', () => leaveCurrentRoom(client));
});

function joinRoom(client, room) {
  client.roomCode = room.code;
  room.players.set(client.id, client);
  send(client.socket, { type: 'room_joined', code: room.code, clientId: client.id });
  broadcastRoom(room);
}

function leaveCurrentRoom(client) {
  if (!client.roomCode) return;
  const room = rooms.get(client.roomCode);
  client.roomCode = null;
  if (!room) return;
  room.players.delete(client.id);
  if (!room.players.size) rooms.delete(room.code);
  else broadcastRoom(room);
}

function broadcastRoom(room) {
  const humans = Array.from(room.players.values()).map((p) => ({ id: p.id, name: p.name }));
  const snapshot = {
    type: 'room_state',
    code: room.code,
    humanCap: HUMAN_CAP,
    humans,
    botCount: PERSISTENT_BOTS,
    totalEntitiesAtCapacity: HUMAN_CAP + PERSISTENT_BOTS,
    authority: 'server',
  };
  for (const player of room.players.values()) send(player.socket, snapshot);
}

function createRoomCode() {
  for (let attempt = 0; attempt < 1000; attempt++) {
    let code = '';
    for (let i = 0; i < 6; i++) code += ROOM_CHARS[Math.floor(Math.random() * ROOM_CHARS.length)];
    if (!rooms.has(code)) return code;
  }
  throw new Error('Unable to allocate room code');
}

function send(socket, payload) {
  if (socket.readyState === 1) socket.send(JSON.stringify(payload));
}

initStore()
  .then(() => server.listen(PORT, () => console.log(`Pirat.io secure server listening on :${PORT}`)))
  .catch((error) => {
    console.error('Failed to initialize profile store', error);
    process.exit(1);
  });
