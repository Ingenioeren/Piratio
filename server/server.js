const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = Number(process.env.PORT || 8080);
const MAX_HUMANS = 20;
const BOT_COUNT = 100;
const TOTAL_ENTITIES = MAX_HUMANS + BOT_COUNT;
const ROOM_CHARS = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

const rooms = new Map();
let nextClientId = 1;

const server = http.createServer((req, res) => {
  if (req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, rooms: rooms.size, maxHumans: MAX_HUMANS, botsPerRoom: BOT_COUNT }));
    return;
  }
  res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('Pirat.io room server');
});

const wss = new WebSocketServer({ server });

wss.on('connection', (socket) => {
  const client = {
    id: `p${nextClientId++}`,
    name: 'Captain',
    roomCode: null,
    socket,
  };

  send(socket, { type: 'connected', clientId: client.id });

  socket.on('message', (raw) => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch {
      send(socket, { type: 'error', code: 'BAD_JSON' });
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
      if (!room) {
        send(socket, { type: 'join_rejected', reason: 'ROOM_NOT_FOUND', code });
        return;
      }
      if (room.players.size >= MAX_HUMANS) {
        send(socket, { type: 'join_rejected', reason: 'ROOM_FULL', code });
        return;
      }
      joinRoom(client, room);
      return;
    }

    if (message.type === 'leave_room') {
      leaveCurrentRoom(client);
      send(socket, { type: 'left_room' });
      return;
    }

    if (message.type === 'ping') {
      send(socket, { type: 'pong', now: Date.now() });
      return;
    }

    send(socket, { type: 'error', code: 'UNKNOWN_MESSAGE' });
  });

  socket.on('close', () => leaveCurrentRoom(client));
});

function joinRoom(client, room) {
  client.roomCode = room.code;
  room.players.set(client.id, client);
  send(client.socket, {
    type: 'room_joined',
    code: room.code,
    clientId: client.id,
  });
  broadcastRoom(room);
}

function leaveCurrentRoom(client) {
  if (!client.roomCode) return;
  const room = rooms.get(client.roomCode);
  client.roomCode = null;
  if (!room) return;

  room.players.delete(client.id);
  if (room.players.size === 0) rooms.delete(room.code);
  else broadcastRoom(room);
}

function broadcastRoom(room) {
  const humans = Array.from(room.players.values()).map((player) => ({
    id: player.id,
    name: player.name,
  }));

  const snapshot = {
    type: 'room_state',
    code: room.code,
    maxHumans: MAX_HUMANS,
    botCount: BOT_COUNT,
    totalEntityCapacity: TOTAL_ENTITIES,
    humans,
    humanSlotsRemaining: MAX_HUMANS - humans.length,
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

function sanitizeName(value) {
  const name = String(value || '').trim().replace(/\s+/g, ' ');
  return (name || 'Captain').slice(0, 16);
}

function send(socket, payload) {
  if (socket.readyState === socket.OPEN) socket.send(JSON.stringify(payload));
}

server.listen(PORT, () => {
  console.log(`Pirat.io room server listening on :${PORT} — ${MAX_HUMANS} humans + ${BOT_COUNT} bots per room`);
});
