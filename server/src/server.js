// RetroLink relay server: online rooms for link cable play.
//
// Each phone runs its own copy of the game. The emulator core (gpSP) exchanges small
// link cable / wireless adapter packets, and this server forwards them between the
// players of a room. It never sees ROMs or save files.
//
// Protocol (one WebSocket per player, at /ws):
//   1. The client sends one JSON text message:
//        {"t":"host","name","game","system","mode","public","maxPlayers","protocol"}
//        {"t":"join","code","name","protocol"}
//   2. The server answers {"t":"hosted"|"joined","clientId","room","players"} or {"t":"error","code","message"}.
//   3. Packets are binary frames. Client to server: [target id u16 BE][payload].
//      Server to client: [sender id u16 BE][payload]. Target 0xFFFF means everyone else.
//   4. Other JSON messages: peer_joined, peer_left, closed, kicked, kick (host only), leave, ping/pong.

import http from 'node:http';
import { WebSocketServer } from 'ws';
import {
  BROADCAST,
  RoomError,
  RoomRegistry,
  describePlayers,
  describeRoom,
  isValidCode,
  normalizeCode,
} from './rooms.js';

const MAX_PAYLOAD = 64 * 1024;
const JOIN_TIMEOUT_MS = 15_000;
const HEARTBEAT_MS = 20_000;
const MAX_BUFFERED_BYTES = 2 * 1024 * 1024;
const MESSAGES_PER_SECOND = 3000;

export function createRelayServer(options = {}) {
  const config = {
    appName: options.appName ?? process.env.APP_NAME ?? 'RetroLink',
    appScheme: options.appScheme ?? process.env.APP_SCHEME ?? 'retrolink',
    playStoreUrl: options.playStoreUrl ?? process.env.PLAY_STORE_URL ?? '',
    androidPackage: options.androidPackage ?? process.env.ANDROID_PACKAGE ?? '',
    androidCertSha256: options.androidCertSha256 ?? process.env.ANDROID_CERT_SHA256 ?? '',
    log: options.log ?? ((...args) => console.log(new Date().toISOString(), ...args)),
  };
  const registry = options.registry ?? new RoomRegistry();

  const httpServer = http.createServer((req, res) => handleHttp(req, res, registry, config));
  const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_PAYLOAD + 2 });

  httpServer.on('upgrade', (req, socket, head) => {
    const { pathname } = new URL(req.url, 'http://localhost');
    if (pathname !== '/ws') {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req));
  });

  wss.on('connection', (ws) => handleConnection(ws, registry, config));

  const heartbeat = setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, HEARTBEAT_MS);
  heartbeat.unref();

  return {
    httpServer,
    registry,
    listen(port, host) {
      return new Promise((resolve) => httpServer.listen(port, host, () => resolve(httpServer.address())));
    },
    close() {
      clearInterval(heartbeat);
      for (const ws of wss.clients) ws.terminate();
      return new Promise((resolve) => httpServer.close(() => resolve()));
    },
  };
}

function sendJson(ws, message) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(message));
}

function handleConnection(ws, registry, config) {
  const state = { room: null, player: null, tokens: MESSAGES_PER_SECOND, lastRefill: Date.now() };
  ws.isAlive = true;
  ws.on('pong', () => {
    ws.isAlive = true;
  });

  const joinTimer = setTimeout(() => {
    if (!state.room) ws.close(4002, 'join timeout');
  }, JOIN_TIMEOUT_MS);

  const fail = (error) => {
    const code = error instanceof RoomError ? error.code : 'bad_request';
    const message = error instanceof RoomError ? error.message : 'Invalid request.';
    sendJson(ws, { t: 'error', code, message });
  };

  ws.on('message', (data, isBinary) => {
    if (!takeToken(state)) {
      ws.close(4003, 'too many messages');
      return;
    }

    if (isBinary) {
      if (state.room) relayPacket(registry, state, data);
      return;
    }

    let message;
    try {
      message = JSON.parse(data.toString('utf8'));
    } catch {
      fail(new RoomError('bad_request', 'Invalid message.'));
      return;
    }
    if (!message || typeof message.t !== 'string') return;

    if (!state.room) {
      try {
        if (message.t === 'host') {
          const { room, player } = registry.createRoom(ws, message);
          state.room = room;
          state.player = player;
          clearTimeout(joinTimer);
          sendJson(ws, { t: 'hosted', clientId: 0, room: describeRoom(room), players: describePlayers(room) });
          config.log(`room ${room.code} created (${room.mode}, ${room.isPublic ? 'public' : 'private'})`);
        } else if (message.t === 'join') {
          const { room, player } = registry.joinRoom(ws, message);
          state.room = room;
          state.player = player;
          clearTimeout(joinTimer);
          sendJson(ws, { t: 'joined', clientId: player.id, room: describeRoom(room), players: describePlayers(room) });
          for (const other of room.players.values()) {
            if (other.id !== player.id) sendJson(other.connection, { t: 'peer_joined', id: player.id, name: player.name });
          }
        } else if (message.t === 'ping') {
          sendJson(ws, { t: 'pong' });
        }
      } catch (error) {
        fail(error);
      }
      return;
    }

    switch (message.t) {
      case 'ping':
        sendJson(ws, { t: 'pong' });
        break;
      case 'leave':
        ws.close(1000, 'left');
        break;
      case 'kick': {
        // Only the host may remove players. The app does this when the emulator core
        // refuses a player, for example when a link cable mode is already full.
        if (state.player.id !== 0 || !Number.isInteger(message.id) || message.id === 0) break;
        const target = state.room.players.get(message.id);
        if (target) {
          sendJson(target.connection, { t: 'kicked', reason: typeof message.reason === 'string' ? message.reason.slice(0, 40) : 'removed' });
          target.connection.close(4001, 'kicked');
        }
        break;
      }
      default:
        break;
    }
  });

  ws.on('close', () => {
    clearTimeout(joinTimer);
    const { room, player } = state;
    if (!room || !player) return;
    state.room = null;
    const result = registry.leave(room, player.id);
    if (!result.removed) return;
    if (result.closed) {
      config.log(`room ${room.code} closed`);
      for (const other of room.players.values()) {
        sendJson(other.connection, { t: 'closed', reason: 'host_left' });
        other.connection.close(4000, 'host left');
      }
    } else {
      for (const other of room.players.values()) sendJson(other.connection, { t: 'peer_left', id: player.id });
    }
  });

  ws.on('error', () => ws.terminate());
}

function takeToken(state) {
  const now = Date.now();
  const elapsed = now - state.lastRefill;
  if (elapsed > 0) {
    state.tokens = Math.min(MESSAGES_PER_SECOND, state.tokens + (elapsed * MESSAGES_PER_SECOND) / 1000);
    state.lastRefill = now;
  }
  if (state.tokens < 1) return false;
  state.tokens -= 1;
  return true;
}

function relayPacket(registry, state, data) {
  const buffer = Buffer.isBuffer(data) ? data : Buffer.concat(Array.isArray(data) ? data : [Buffer.from(data)]);
  if (buffer.length < 3 || buffer.length > MAX_PAYLOAD + 2) return;
  const target = buffer.readUInt16BE(0);
  const fromId = state.player.id;

  const recipients = registry.recipients(state.room, fromId, target);
  if (recipients.length === 0) return;

  const out = Buffer.allocUnsafe(buffer.length);
  out.writeUInt16BE(fromId, 0);
  buffer.copy(out, 2, 2);

  for (const recipient of recipients) {
    const ws = recipient.connection;
    if (ws.readyState !== ws.OPEN) continue;
    if (ws.bufferedAmount > MAX_BUFFERED_BYTES) {
      // The link would desync anyway; drop the slow player instead of using all memory.
      ws.close(4004, 'connection too slow');
      continue;
    }
    ws.send(out, { binary: true });
  }
}

// ---------------------------------------------------------------------------
// HTTP: public room list, invite pages, Android App Links and reports.

function handleHttp(req, res, registry, config) {
  const url = new URL(req.url, 'http://localhost');
  const path = url.pathname;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, corsHeaders());
    res.end();
    return;
  }

  if (req.method === 'GET' && path === '/health') {
    sendText(res, 200, 'ok');
    return;
  }

  if (req.method === 'GET' && path === '/api/rooms') {
    const system = url.searchParams.get('system');
    const rooms = registry.publicRooms().filter((room) => !system || room.system === system);
    sendJsonResponse(res, 200, { rooms });
    return;
  }

  if (req.method === 'GET' && path.startsWith('/api/rooms/')) {
    const code = normalizeCode(decodeURIComponent(path.slice('/api/rooms/'.length)));
    const room = isValidCode(code) ? registry.rooms.get(code) : undefined;
    if (!room) sendJsonResponse(res, 404, { error: 'not_found' });
    else sendJsonResponse(res, 200, { room: describeRoom(room) });
    return;
  }

  if (req.method === 'POST' && path === '/api/report') {
    readBody(req, 2048)
      .then((body) => {
        const report = JSON.parse(body || '{}');
        const code = normalizeCode(report.code);
        const reason = typeof report.reason === 'string' ? report.reason.slice(0, 300) : '';
        config.log(`REPORT room=${isValidCode(code) ? code : '?'} reason=${JSON.stringify(reason)}`);
        res.writeHead(204, corsHeaders());
        res.end();
      })
      .catch(() => sendJsonResponse(res, 400, { error: 'bad_request' }));
    return;
  }

  if (req.method === 'GET' && path === '/.well-known/assetlinks.json') {
    if (!config.androidPackage || !config.androidCertSha256) {
      sendJsonResponse(res, 404, []);
      return;
    }
    sendJsonResponse(res, 200, [
      {
        relation: ['delegate_permission/common.handle_all_urls'],
        target: {
          namespace: 'android_app',
          package_name: config.androidPackage,
          sha256_cert_fingerprints: config.androidCertSha256.split(',').map((s) => s.trim()),
        },
      },
    ]);
    return;
  }

  if (req.method === 'GET' && (path.startsWith('/r/') || path.startsWith('/join/'))) {
    const code = normalizeCode(path.split('/')[2] ?? '');
    sendHtml(res, isValidCode(code) ? 200 : 404, invitePage(config, isValidCode(code) ? code : '', registry));
    return;
  }

  if (req.method === 'GET' && path === '/') {
    sendHtml(res, 200, invitePage(config, '', registry));
    return;
  }

  sendText(res, 404, 'not found');
}

function corsHeaders() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
  };
}

function sendText(res, status, text) {
  res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8', ...corsHeaders() });
  res.end(text);
}

function sendJsonResponse(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders() });
  res.end(JSON.stringify(body));
}

function sendHtml(res, status, html) {
  res.writeHead(status, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'",
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(html);
}

function readBody(req, limit) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
}

function invitePage(config, code, registry) {
  const room = code ? registry.rooms.get(code) : undefined;
  const appName = escapeHtml(config.appName);
  const title = code ? `Join room ${code}` : appName;
  const details = room
    ? `<p class="game">${escapeHtml(room.game)}</p><p>${room.players.size}/${room.maxPlayers} players</p>`
    : code
      ? '<p>This room is closed or the code is wrong.</p>'
      : '<p>Play link cable games online with friends.</p>';
  const openLink = code ? `<a class="button" href="${escapeHtml(config.appScheme)}://join/${code}">Open in ${appName}</a>` : '';
  const store = config.playStoreUrl ? `<a class="link" href="${escapeHtml(config.playStoreUrl)}">Get ${appName} on Google Play</a>` : '';
  const codeBox = code ? `<p>Room code</p><p class="code">${code}</p>` : '';

  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escapeHtml(title)} - ${appName}</title>
<style>
:root{color-scheme:light dark;--bg:#f6f5f2;--fg:#1d1c1a;--muted:#6b6862;--accent:#3b5bdb;--card:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#141413;--fg:#f2f1ee;--muted:#a3a09a;--accent:#91a7ff;--card:#1f1e1c}}
body{margin:0;font-family:system-ui,sans-serif;background:var(--bg);color:var(--fg);display:flex;min-height:100vh;align-items:center;justify-content:center}
main{background:var(--card);padding:32px 24px;border-radius:16px;max-width:360px;width:calc(100% - 32px);text-align:center;box-shadow:0 1px 3px rgba(0,0,0,.12)}
h1{font-size:22px;margin:0 0 12px}p{color:var(--muted);margin:6px 0}.game{color:var(--fg);font-weight:600}
.code{font:700 32px ui-monospace,monospace;letter-spacing:4px;color:var(--fg)}
.button{display:block;margin:20px 0 12px;padding:14px;border-radius:10px;background:var(--accent);color:#fff;text-decoration:none;font-weight:600}
.link{color:var(--accent)}
</style></head>
<body><main><h1>${escapeHtml(title)}</h1>${details}${codeBox}${openLink}${store}</main></body></html>`;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 8080);
  const server = createRelayServer();
  server.listen(port, '0.0.0.0').then((address) => {
    console.log(`RetroLink relay listening on port ${address.port}`);
  });
  const shutdown = () => server.close().then(() => process.exit(0));
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
