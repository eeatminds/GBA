// GBA/GBC Emulator relay server: online rooms for link cable play.
//
// Each phone runs its own copy of the game. The emulator core (gpSP) exchanges small
// link cable / wireless adapter packets, and this server forwards them between the
// players of a room. It never sees ROMs or save files.
//
// Protocol (one WebSocket per player, at /ws):
//   1. The client sends one JSON text message:
//        {"t":"host","kind":"link"|"netplay","name","game","system","core","mode","delay","public","maxPlayers","protocol","world"}
//        {"t":"join","code","name","protocol"}
//        {"t":"resume","code","id","token","protocol"}  (take your seat back after losing the connection)
//   2. The server answers {"t":"hosted"|"joined","clientId","token","room","players"} or {"t":"error","code","message"}.
//   3. Packets are binary frames. Client to server: [target id u16 BE][payload].
//      Server to client: [sender id u16 BE][payload]. Target 0xFFFF means everyone else.
//   4. Other JSON messages: peer_joined, peer_left, peer_away, peer_back, closed, kicked, kick (owner only),
//      leave, ping/pong, pos (where a player is in their game), link_* (link cables),
//      invite / invite_reply (asking another player to trade or battle), name / renamed (a new
//      player name, e.g. the trainer name read from the running game).
//
// In trade & battle rooms a player whose connection drops keeps their seat (and link) for
// RESUME_GRACE_MS, and an empty room stays open for EMPTY_ROOM_MS, so a phone that briefly
// loses its connection, or a creator who steps out, doesn't end the room.

import fs from 'node:fs';
import http from 'node:http';
import nodePath from 'node:path';
import { WebSocketServer } from 'ws';
import {
  BROADCAST,
  RoomError,
  RoomRegistry,
  cleanName,
  describePlayers,
  describeGroups,
  describeRoom,
  isValidCode,
  normalizeCode,
} from './rooms.js';

const MAX_PAYLOAD = 64 * 1024;
const JOIN_TIMEOUT_MS = 15_000;
const HEARTBEAT_MS = 20_000;
const RESUME_GRACE_MS = 3 * 60_000;
const SWEEP_MS = 30_000;
// Game states for 'play together' rooms can be a few MB, sent in 60 KB chunks.
const MAX_BUFFERED_BYTES = 16 * 1024 * 1024;
const MESSAGES_PER_SECOND = 3000;

export function createRelayServer(options = {}) {
  const config = {
    appName: options.appName ?? process.env.APP_NAME ?? 'GBA/GBC Emulator',
    appScheme: options.appScheme ?? process.env.APP_SCHEME ?? 'retrolink',
    playStoreUrl: options.playStoreUrl ?? process.env.PLAY_STORE_URL ?? '',
    androidPackage: options.androidPackage ?? process.env.ANDROID_PACKAGE ?? '',
    androidCertSha256: options.androidCertSha256 ?? process.env.ANDROID_CERT_SHA256 ?? '',
    log: options.log ?? ((...args) => console.log(new Date().toISOString(), ...args)),
    // Pictures for the games page the server's owner may add: <game id>.webp, .png or .jpg.
    coversDir: options.coversDir ?? process.env.COVERS_DIR ?? '/etc/gba-rooms/covers',
    resumeGraceMs: options.resumeGraceMs ?? RESUME_GRACE_MS,
  };
  const registry = options.registry ?? new RoomRegistry({ emptyRoomMs: options.emptyRoomMs });
  // Seats held for players who lost their connection: "CODE:id" -> timer.
  const awayTimers = new Map();

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

  wss.on('connection', (ws) => handleConnection(ws, registry, config, awayTimers));

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

  // Empty trade & battle rooms close after a while.
  const sweeper = setInterval(() => {
    for (const code of registry.sweep()) config.log(`room ${code} closed (empty)`);
  }, options.sweepMs ?? SWEEP_MS);
  sweeper.unref();

  return {
    httpServer,
    registry,
    listen(port, host) {
      return new Promise((resolve) => httpServer.listen(port, host, () => resolve(httpServer.address())));
    },
    close() {
      clearInterval(heartbeat);
      clearInterval(sweeper);
      for (const timer of awayTimers.values()) clearTimeout(timer);
      awayTimers.clear();
      for (const ws of wss.clients) ws.terminate();
      return new Promise((resolve) => httpServer.close(() => resolve()));
    },
  };
}

function sendJson(ws, message) {
  if (ws && ws.readyState === ws.OPEN) ws.send(JSON.stringify(message));
}

function handleConnection(ws, registry, config, awayTimers) {
  // leaving: the player said goodbye. replaced: the same player came back on a new connection.
  const state = { room: null, player: null, tokens: MESSAGES_PER_SECOND, lastRefill: Date.now(), leaving: false, replaced: false };
  ws.relayState = state;
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
          sendJson(ws, { t: 'hosted', clientId: 0, token: player.token, ...roomSnapshot(room) });
          config.log(`room ${room.code} created (${room.mode}, ${room.isPublic ? 'public' : 'private'})`);
        } else if (message.t === 'join') {
          const { room, player, groupsChanged, ownerChanged } = registry.joinRoom(ws, message);
          state.room = room;
          state.player = player;
          clearTimeout(joinTimer);
          sendJson(ws, { t: 'joined', clientId: player.id, token: player.token, ...roomSnapshot(room) });
          for (const other of room.players.values()) {
            if (other.id !== player.id) sendJson(other.connection, { t: 'peer_joined', id: player.id, name: player.name });
          }
          if (groupsChanged) broadcastGroups(room);
          if (ownerChanged) broadcastToRoom(room, { t: 'owner', id: room.ownerId });
        } else if (message.t === 'resume') {
          const { room, player, previous } = registry.resume(ws, message);
          const key = `${room.code}:${player.id}`;
          clearTimeout(awayTimers.get(key));
          awayTimers.delete(key);
          // The old connection may still look open: retire it without freeing the seat.
          if (previous && previous !== ws) {
            if (previous.relayState) previous.relayState.replaced = true;
            previous.terminate();
          }
          state.room = room;
          state.player = player;
          clearTimeout(joinTimer);
          sendJson(ws, { t: 'joined', clientId: player.id, token: player.token, resumed: true, ...roomSnapshot(room) });
          for (const other of room.players.values()) {
            if (other.id !== player.id) sendJson(other.connection, { t: 'peer_back', id: player.id });
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
        state.leaving = true;
        ws.close(1000, 'left');
        break;
      case 'pos':
        relayPresence(state, message);
        break;
      case 'link_request':
      case 'link_accept':
      case 'link_decline':
      case 'unlink':
        handleLinkMessage(registry, state, message);
        break;
      case 'invite':
      case 'invite_reply':
        handleInvite(registry, state, message);
        break;
      case 'name': {
        // The trainer name from the player's game, read once the game is running.
        const name = cleanName(message.name, '');
        if (!name || name === state.player.name) break;
        state.player.name = name;
        for (const other of state.room.players.values()) {
          if (other.id !== state.player.id) sendJson(other.connection, { t: 'renamed', id: state.player.id, name });
        }
        break;
      }
      case 'kick': {
        // Only the room's owner may remove players.
        const room = state.room;
        if (state.player.id !== room.ownerId || !Number.isInteger(message.id) || message.id === state.player.id) break;
        const target = state.room.players.get(message.id);
        if (target) {
          if (target.connection) {
            if (target.connection.relayState) target.connection.relayState.leaving = true;
            sendJson(target.connection, { t: 'kicked', reason: typeof message.reason === 'string' ? message.reason.slice(0, 40) : 'removed' });
            target.connection.close(4001, 'kicked');
          } else {
            removePlayer(registry, config, awayTimers, room, target.id);
          }
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
    if (!room || !player || state.replaced) return;
    state.room = null;
    // Someone else (this player, reconnected) holds the seat now.
    if (player.connection && player.connection !== ws) return;
    // A trade & battle player who dropped (no goodbye) keeps their seat for a while.
    if (!state.leaving && room.kind === 'link' && player.connection === ws && registry.markAway(room, player.id)) {
      broadcastToRoom(room, { t: 'peer_away', id: player.id });
      const key = `${room.code}:${player.id}`;
      clearTimeout(awayTimers.get(key));
      const timer = setTimeout(() => {
        awayTimers.delete(key);
        if (room.players.get(player.id)?.away) removePlayer(registry, config, awayTimers, room, player.id);
      }, config.resumeGraceMs);
      timer.unref();
      awayTimers.set(key, timer);
      return;
    }
    removePlayer(registry, config, awayTimers, room, player.id);
  });

  ws.on('error', () => ws.terminate());
}

// Takes a player out of a room for good and tells everyone else.
function removePlayer(registry, config, awayTimers, room, playerId) {
  const key = `${room.code}:${playerId}`;
  clearTimeout(awayTimers.get(key));
  awayTimers.delete(key);
  const result = registry.leave(room, playerId);
  if (!result.removed) return;
  if (result.closed) {
    config.log(`room ${room.code} closed`);
    for (const other of room.players.values()) {
      sendJson(other.connection, { t: 'closed', reason: 'host_left' });
      other.connection?.close(4000, 'host left');
    }
  } else {
    for (const other of room.players.values()) sendJson(other.connection, { t: 'peer_left', id: playerId });
    if (result.groupsChanged) broadcastGroups(room);
    if (result.ownerChanged) broadcastToRoom(room, { t: 'owner', id: room.ownerId });
  }
}

function roomSnapshot(room) {
  return { room: describeRoom(room), players: describePlayers(room), groups: describeGroups(room), ownerId: room.ownerId };
}

function broadcastToRoom(room, message) {
  for (const player of room.players.values()) sendJson(player.connection, message);
}

function broadcastGroups(room) {
  broadcastToRoom(room, { t: 'groups', groups: describeGroups(room) });
}

// Link cables inside a room: ask a player, they accept or decline, either can unplug.
// Problems go back to the sender as 'link_error' so the game keeps running.
function handleLinkMessage(registry, state, message) {
  const { room, player } = state;
  const otherId = Number.isInteger(message.id) ? message.id : -1;
  try {
    switch (message.t) {
      case 'link_request': {
        registry.requestLink(room, player.id, otherId);
        sendJson(room.players.get(otherId).connection, { t: 'link_request', from: player.id, name: player.name });
        break;
      }
      case 'link_accept': {
        registry.acceptLink(room, player.id, otherId);
        broadcastGroups(room);
        break;
      }
      case 'link_decline': {
        if (registry.declineLink(room, player.id, otherId)) {
          const requester = room.players.get(otherId);
          if (requester) sendJson(requester.connection, { t: 'link_declined', id: player.id });
        }
        break;
      }
      case 'unlink': {
        // A player unplugs themself. The leader of a link may also unplug another member,
        // which the emulator asks for when that player cannot join the link.
        let target = player.id;
        if (otherId >= 0 && otherId !== player.id) {
          const group = registry.groupOf(room, player.id);
          const isLeader = group?.some((member) => member.id === player.id && member.slot === 0);
          if (!isLeader || !group.some((member) => member.id === otherId)) break;
          target = otherId;
        }
        if (registry.removeFromGroup(room, target)) broadcastGroups(room);
        break;
      }
      default:
        break;
    }
  } catch (error) {
    if (error instanceof RoomError) sendJson(player.connection, { t: 'link_error', code: error.code, message: error.message });
  }
}

// Talking to another player in the game: "trade or battle?", and their answer. Saying yes
// links the two of them so they can meet at the Cable Club.
function handleInvite(registry, state, message) {
  const { room, player } = state;
  const otherId = Number.isInteger(message.id) ? message.id : -1;
  const other = room.players.get(otherId);
  if (!other || otherId === player.id) return;
  try {
    if (message.t === 'invite') {
      const kind = registry.invite(room, player.id, otherId, message.kind);
      sendJson(other.connection, { t: 'invite', from: player.id, name: player.name, kind });
    } else {
      const accept = message.accept === true;
      const answer = registry.answerInvite(room, player.id, otherId, accept);
      if (!answer) return;
      if (answer.groupsChanged) broadcastGroups(room);
      sendJson(other.connection, { t: 'invite_reply', from: player.id, accept, kind: answer.kind });
    }
  } catch (error) {
    if (error instanceof RoomError) sendJson(player.connection, { t: 'link_error', code: error.code, message: error.message });
  }
}

// "Same world": where a player's character is in their game, passed to everyone else in the
// room so their app can draw it. Only known fields, with sane values, get through.
const WORD = /^[a-z0-9._-]{1,24}$/;

function relayPresence(state, message) {
  const { room, player } = state;
  if (room.kind !== 'link' || typeof message.w !== 'string' || !WORD.test(message.w)) return;
  const out = { t: 'pos', from: player.id, w: message.w };
  if (message.m !== undefined) {
    if (typeof message.m !== 'string' || !WORD.test(message.m)) return;
    const numbers = ['x', 'y', 's', 'f', 'h'].map((key) => message[key]);
    if (!numbers.every((n) => Number.isInteger(n) && Math.abs(n) <= 1_000_000)) return;
    out.m = message.m;
    [out.x, out.y, out.s, out.f, out.h] = numbers;
  }
  // When the sender read its game (its own clock, milliseconds): others replay its walk at that pace.
  if (Number.isSafeInteger(message.ts) && message.ts >= 0) out.ts = message.ts;
  const text = JSON.stringify(out);
  for (const other of room.players.values()) {
    if (other.id !== player.id && other.connection && other.connection.readyState === other.connection.OPEN) other.connection.send(text);
  }
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
// HTTP: public room list, invite pages, Android App Links, reports, and link reports
// (POST /api/linklog from a phone whose trade or battle did not start; GET /api/linklog/CODE).

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

  // Link reports: when a trade or battle in a room doesn't get going, each phone sends what
  // its link did (events and numbers, no game data), kept here a few hours to look into it.
  if (req.method === 'POST' && path === '/api/linklog') {
    readBody(req, LINK_LOG_MAX_BYTES)
      .then((body) => {
        const report = JSON.parse(body || '{}');
        const code = normalizeCode(report.code);
        if (!isValidCode(code) || typeof report.text !== 'string') throw new Error('bad');
        addLinkLog(code, {
          at: new Date().toISOString(),
          id: Number.isInteger(report.id) ? report.id : -1,
          name: cleanName(report.name, '?'),
          reason: typeof report.reason === 'string' ? report.reason.slice(0, 200) : '',
          text: report.text.slice(0, LINK_LOG_MAX_BYTES),
        });
        config.log(`LINKLOG room=${code} player=${report.id} reason=${JSON.stringify(String(report.reason || '').slice(0, 80))}`);
        res.writeHead(204, corsHeaders());
        res.end();
      })
      .catch(() => sendJsonResponse(res, 400, { error: 'bad_request' }));
    return;
  }

  if (req.method === 'GET' && path.startsWith('/api/linklog/')) {
    const code = normalizeCode(decodeURIComponent(path.slice('/api/linklog/'.length)));
    const logs = isValidCode(code) ? linkLogsFor(code) : [];
    const text = logs.length
      ? logs.map((log) => `==== ${log.at} player ${log.id} (${log.name}): ${log.reason}\n${log.text}`).join('\n\n')
      : 'No link reports for this room.';
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...corsHeaders() });
    res.end(text);
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

  if (req.method === 'GET' && (path === '/games' || path === '/games/')) {
    sendHtml(res, 200, gamesPage(config, registry), "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'");
    return;
  }

  if (req.method === 'GET' && path.startsWith('/covers/')) {
    serveCover(res, config, path.slice('/covers/'.length));
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

function sendHtml(res, status, html, csp = "default-src 'none'; style-src 'unsafe-inline'") {
  res.writeHead(status, {
    'Content-Type': 'text/html; charset=utf-8',
    'Content-Security-Policy': csp,
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(html);
}

const LINK_LOG_MAX_BYTES = 200_000;
const LINK_LOGS_PER_ROOM = 12;
const LINK_LOG_ROOMS = 200;
const LINK_LOG_KEEP_MS = 6 * 60 * 60 * 1000;
const linkLogs = new Map();

function addLinkLog(code, log) {
  const logs = (linkLogs.get(code) || []).concat({ ...log, time: Date.now() }).slice(-LINK_LOGS_PER_ROOM);
  linkLogs.delete(code);
  linkLogs.set(code, logs);
  // Oldest rooms go first.
  while (linkLogs.size > LINK_LOG_ROOMS) linkLogs.delete(linkLogs.keys().next().value);
}

function linkLogsFor(code) {
  const now = Date.now();
  return (linkLogs.get(code) || []).filter((log) => now - log.time < LINK_LOG_KEEP_MS);
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
  const gamesLink = `<a class="link games" href="/games">Games you can play together</a>`;

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
.link{color:var(--accent)}.games{display:block;margin-top:16px}
</style></head>
<body><main><h1>${escapeHtml(title)}</h1>${details}${codeBox}${openLink}${store}${gamesLink}</main></body></html>`;
}

// The games players can play together, grouped as the real games link: every game in a
// family trades and battles with the others in it. A game is listed here only once trading
// and battling have been tested.
// The games on the games page. `cover`: the game's box art in the libretro thumbnails library (the one
// the app shows game covers from), as [system, name]; deploy/fetch-covers.js puts it in the covers folder.
export const GAME_FAMILIES = [
  {
    console: 'Game Boy games',
    short: 'GB',
    blurb:
      'Red, Blue and Yellow players share a room and can all trade and battle each other, ' +
      'just as over a real link cable. On the map you see everyone in Kanto.',
    systems: ['gb', 'gbc'],
    regions: [{ name: 'Kanto', games: ['red', 'blue', 'yellow'] }],
    games: [
      { id: 'red', name: 'Red', match: ['red'], emblem: 'star', colors: ['#7a1414', '#e34234'], versions: 'USA / Europe',
        cover: ['Nintendo - Game Boy', 'Pokemon - Red Version (USA, Europe) (SGB Enhanced)'] },
      { id: 'blue', name: 'Blue', match: ['blue'], emblem: 'wave', colors: ['#173a7a', '#4a7fe0'], versions: 'USA / Europe',
        cover: ['Nintendo - Game Boy', 'Pokemon - Blue Version (USA, Europe) (SGB Enhanced)'] },
      { id: 'yellow', name: 'Yellow', match: ['yellow'], emblem: 'spark', colors: ['#8a6d00', '#f5cf2a'], versions: 'USA / Europe',
        cover: ['Nintendo - Game Boy', 'Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced)'] },
    ],
  },
  {
    console: 'Game Boy Color games',
    short: 'GBC',
    blurb:
      'Gold, Silver and Crystal players share a room and can all trade and battle each other, ' +
      'just as over a real link cable. On the map you see everyone in Johto.',
    systems: ['gb', 'gbc'],
    regions: [{ name: 'Johto', games: ['gold', 'silver', 'crystal'] }],
    games: [
      { id: 'gold', name: 'Gold', match: ['gold'], emblem: 'sun', colors: ['#7a5a00', '#e0b62c'], versions: 'USA / Europe',
        cover: ['Nintendo - Game Boy Color', 'Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible)'] },
      { id: 'silver', name: 'Silver', match: ['silver'], emblem: 'moon', colors: ['#3d4652', '#b9c3cd'], versions: 'USA / Europe',
        cover: ['Nintendo - Game Boy Color', 'Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible)'] },
      { id: 'crystal', name: 'Crystal', match: ['crystal'], emblem: 'gem', colors: ['#0f4c6e', '#55c1e8'], versions: 'USA / Europe, Rev 1',
        cover: ['Nintendo - Game Boy Color', 'Pokemon - Crystal Version (USA, Europe) (Rev 1)'] },
    ],
  },
  {
    console: 'Game Boy Advance games',
    short: 'GBA',
    blurb:
      'FireRed, LeafGreen, Ruby, Sapphire and Emerald players share a room and can all trade and battle each other, ' +
      'just as over a real link cable. On the map you see the players in your region: Kanto (FireRed and LeafGreen) ' +
      'or Hoenn (Ruby and Sapphire together; Emerald players see each other). As in the real games, trading between ' +
      'Kanto and Hoenn opens up later in the story.',
    systems: ['gba'],
    regions: [
      { name: 'Kanto', games: ['firered', 'leafgreen'] },
      { name: 'Hoenn', games: ['ruby', 'sapphire'] },
      { name: 'Hoenn', games: ['emerald'] },
    ],
    games: [
      { id: 'firered', name: 'FireRed', match: ['firered', 'fire red'], emblem: 'flame', colors: ['#8a1c0c', '#f2703a'], versions: 'USA / Europe, Rev 1',
        cover: ['Nintendo - Game Boy Advance', 'Pokemon - FireRed Version (USA, Europe) (Rev 1)'] },
      { id: 'leafgreen', name: 'LeafGreen', match: ['leafgreen', 'leaf green'], emblem: 'leaf', colors: ['#1f5a1c', '#7ccb5b'], versions: 'USA, Rev 1',
        cover: ['Nintendo - Game Boy Advance', 'Pokemon - LeafGreen Version (USA, Europe) (Rev 1)'] },
      { id: 'ruby', name: 'Ruby', match: ['ruby'], emblem: 'round', colors: ['#7d0f24', '#e8475f'], versions: 'USA, Rev 1',
        cover: ['Nintendo - Game Boy Advance', 'Pokemon - Ruby Version (USA, Europe) (Rev 2)'] },
      { id: 'sapphire', name: 'Sapphire', match: ['sapphire'], emblem: 'drop', colors: ['#12306e', '#4f8ef0'], versions: 'USA / Europe, Rev 2',
        cover: ['Nintendo - Game Boy Advance', 'Pokemon - Sapphire Version (USA, Europe) (Rev 2)'] },
      { id: 'emerald', name: 'Emerald', match: ['emerald'], emblem: 'cut', colors: ['#0d5a3c', '#3fca8c'], versions: 'USA / Europe',
        cover: ['Nintendo - Game Boy Advance', 'Pokemon - Emerald Version (USA, Europe)'] },
    ],
  },
  {
    console: 'ROM hacks',
    short: 'GBA',
    blurb:
      'Fan-made games built on FireRed (Game Boy Advance). Each hack is its own world: you meet, trade and battle ' +
      'players of the same hack and version, not players of the original games or of another hack. ' +
      "Unbound's own Cable Club doesn't trade (the hack turned that off): trade in Multiplayer instead.",
    systems: ['gba'],
    together: 'Each one trades and battles with players of the same hack',
    regions: [
      { name: 'Radical Red', games: ['radicalred'] },
      { name: 'Unbound', games: ['unbound'] },
    ],
    games: [
      { id: 'radicalred', name: 'Radical Red', match: ['radical red', 'radicalred'], emblem: 'bolt', colors: ['#5a0d12', '#e0473f'], versions: 'GBA · Version 4.1' },
      { id: 'unbound', name: 'Unbound', match: ['unbound'], emblem: 'compass', colors: ['#24164a', '#8a6be0'], versions: 'GBA · Version 2.1.1.1' },
    ],
  },
];

export const COVER_TYPES = { webp: 'image/webp', png: 'image/png', jpg: 'image/jpeg' };

// Where [game]'s box art can be downloaded from, or null when it has none there (the ROM hacks).
export function coverSourceUrl(game) {
  if (!game.cover) return null;
  const [system, name] = game.cover;
  // The library writes these characters as "_" in its file names.
  const file = name.replace(/[&*/:`<>?\\|"]/g, '_');
  return `https://thumbnails.libretro.com/${encodeURIComponent(system)}/Named_Boxarts/${encodeURIComponent(file)}.png`;
}

// A picture the server's owner put in the covers folder for this game, if any.
function coverFile(config, id) {
  for (const ext of Object.keys(COVER_TYPES)) {
    const file = nodePath.join(config.coversDir, `${id}.${ext}`);
    if (fs.existsSync(file)) return { file, ext };
  }
  return null;
}

function serveCover(res, config, name) {
  const match = /^([a-z]+)\.(webp|png|jpg)$/.exec(name);
  const known = match && GAME_FAMILIES.some((f) => f.games.some((g) => g.id === match[1]));
  const cover = known ? coverFile(config, match[1]) : null;
  if (!cover || cover.ext !== match[2]) {
    sendText(res, 404, 'not found');
    return;
  }
  res.writeHead(200, { 'Content-Type': COVER_TYPES[cover.ext], 'Cache-Control': 'public, max-age=3600', 'X-Content-Type-Options': 'nosniff' });
  fs.createReadStream(cover.file).pipe(res);
}

// Open rooms per game (any room with someone in it), for the "N open" badges.
function openRooms(registry, family, game) {
  let count = 0;
  for (const room of registry.rooms.values()) {
    const title = String(room.game || '').toLowerCase();
    if (room.players.size > 0 && family.systems.includes(room.system) && game.match.some((m) => title.includes(m))) count++;
  }
  return count;
}

// Original tile art: the game's colours and a simple emblem (no game artwork).
const EMBLEMS = {
  sun:
    '<circle cx="50" cy="52" r="15" fill="#fff6d6"/>' +
    [0, 45, 90, 135, 180, 225, 270, 315]
      .map((a) => `<rect x="48" y="20" width="4" height="12" rx="2" fill="#fff6d6" transform="rotate(${a} 50 52)"/>`)
      .join(''),
  moon:
    '<mask id="moon-cut"><rect width="100" height="100" fill="#fff"/><circle cx="61" cy="44" r="18" fill="#000"/></mask>' +
    '<circle cx="50" cy="52" r="22" fill="#f4f7fa" mask="url(#moon-cut)"/><circle cx="70" cy="30" r="2" fill="#fff"/><circle cx="76" cy="44" r="1.5" fill="#fff"/>',
  gem:
    '<path d="M50 24l20 14v28l-20 14l-20-14V38z" fill="#e8f8ff"/>' +
    '<path d="M50 24v56M30 38l20 14l20-14M30 66l20-14l20 14" stroke="#55c1e8" stroke-width="2" fill="none" opacity=".7"/>',
  flame:
    '<path d="M50 20c4 12 20 20 20 38a20 20 0 0 1-40 0c0-10 6-16 9-23c2 7 5 10 9 11c-3-9-1-18 2-26z" fill="#fff1e3"/>' +
    '<path d="M50 50c2 6 9 9 9 17a9 9 0 0 1-18 0c0-5 3-8 5-11c1 3 2 4 4 5c-1-4 0-8 0-11z" fill="#f2703a" opacity=".8"/>',
  // Three cuts of gem: round, drop and step cut.
  round:
    '<circle cx="50" cy="52" r="24" fill="#ffe9ee"/>' +
    '<path d="M50 28l14 10l6 14l-6 14l-14 10l-14-10l-6-14l6-14z" fill="none" stroke="#e8475f" stroke-width="2" opacity=".75"/>' +
    '<path d="M36 38h28M30 52h40M36 66h28M50 28v48" stroke="#e8475f" stroke-width="1.6" opacity=".45"/>',
  drop:
    '<path d="M50 22c12 16 22 26 22 38a22 22 0 0 1-44 0c0-12 10-22 22-38z" fill="#e8f1ff"/>' +
    '<path d="M50 22l-10 30l10 30l10-30zM28 60l12-8M72 60l-12-8" stroke="#4f8ef0" stroke-width="2" fill="none" opacity=".7"/>',
  cut:
    '<path d="M34 26h32l10 10v32l-10 10H34L24 68V36z" fill="#e6fff3"/>' +
    '<path d="M38 36h24l6 6v20l-6 6H38l-6-6V42zM24 36l8 6M76 36l-8 6M24 68l8-6M76 68l-8-6" stroke="#3fca8c" stroke-width="2" fill="none" opacity=".75"/>',
  // Game Boy: a star, waves and a sparkle.
  star:
    '<path d="M50 22l8.2 17.6l19.3 2.3l-14.2 13.2l3.7 19.1L50 64.8l-17 9.4l3.7-19.1L22.5 41.9l19.3-2.3z" fill="#fff0ee"/>' +
    '<circle cx="50" cy="50" r="6" fill="#e34234" opacity=".55"/>',
  wave:
    '<path d="M22 38c7-8 14-8 21 0s14 8 21 0s10-7 14-3M22 52c7-8 14-8 21 0s14 8 21 0s10-7 14-3M22 66c7-8 14-8 21 0s14 8 21 0s10-7 14-3" ' +
    'stroke="#eef4ff" stroke-width="5" stroke-linecap="round" fill="none"/>',
  spark:
    '<path d="M48 22c3 18 11 26 28 30c-17 4-25 12-28 30c-3-18-11-26-28-30c17-4 25-12 28-30z" fill="#fffbe6"/>' +
    '<path d="M74 20c1 5 3 7 8 8c-5 1-7 3-8 8c-1-5-3-7-8-8c5-1 7-3 8-8z" fill="#fffbe6" opacity=".8"/>',
  // ROM hacks: a lightning bolt and a compass rose.
  bolt:
    '<path d="M56 18L30 56h16l-6 28l28-40H52z" fill="#fff0ee"/>' +
    '<path d="M56 18L30 56h16l-6 28" stroke="#e0473f" stroke-width="2" fill="none" opacity=".6"/>',
  compass:
    '<circle cx="50" cy="52" r="24" fill="none" stroke="#f1ecff" stroke-width="3"/>' +
    '<path d="M50 24l7 21l-7 7l-7-7zM50 80l-7-21l7-7l7 7z" fill="#f1ecff"/>' +
    '<path d="M22 52l21-7l7 7l-7 7zM78 52l-21 7l-7-7l7-7z" fill="#f1ecff" opacity=".6"/>',
  leaf:
    '<path d="M28 74C26 44 46 26 76 24c2 30-16 50-48 50z" fill="#effbe7"/>' +
    '<path d="M28 74L66 36M44 58l-2-12M54 48l-1-10M44 58l12 1M54 48l11 1" stroke="#7ccb5b" stroke-width="2.4" stroke-linecap="round" fill="none"/>',
};

function tileArt(game, short) {
  const [dark, light] = game.colors;
  return `<svg viewBox="0 0 100 100" role="img" aria-label="${escapeHtml(game.name)}">
<defs><linearGradient id="g-${game.id}" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${light}"/><stop offset="1" stop-color="${dark}"/></linearGradient></defs>
<rect width="100" height="100" fill="url(#g-${game.id})"/>
<circle cx="50" cy="52" r="34" fill="#fff" opacity=".12"/>${EMBLEMS[game.emblem]}
<text x="8" y="14" font-family="system-ui,sans-serif" font-size="8" font-weight="700" fill="#fff" opacity=".85">${short}</text>
<text x="50" y="93" text-anchor="middle" font-family="system-ui,sans-serif" font-size="11" font-weight="800" fill="#fff" letter-spacing=".5">${escapeHtml(game.name.toUpperCase())}</text></svg>`;
}

function listNames(names) {
  return names.length > 1 ? `${names.slice(0, -1).join(', ')} and ${names.at(-1)}` : names[0];
}

function gamesPage(config, registry) {
  const appName = escapeHtml(config.appName);
  const linkIcon =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" ' +
    'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
  const families = GAME_FAMILIES.map((family) => {
    const tile = (game) => {
      const cover = coverFile(config, game.id);
      const art = cover ? `<img src="/covers/${game.id}.${cover.ext}" alt="${escapeHtml(game.name)}" width="256" height="256" loading="lazy">` : tileArt(game, family.short);
      const open = openRooms(registry, family, game);
      const badge = open > 0 ? `<span class="open"><i></i>${open} open</span>` : '';
      return `<li class="tile"><div class="art">${art}${badge}</div><b>${escapeHtml(game.name)}</b><small>${escapeHtml(game.versions)}</small></li>`;
    };
    // One row of tiles per region: the players who see each other on the map.
    const regions = family.regions
      .map((region) => {
        const tiles = region.games.map((id) => tile(family.games.find((game) => game.id === id))).join('');
        return `<ul class="tiles">${tiles}</ul><p class="region"><span>See each other in ${escapeHtml(region.name)}</span></p>`;
      })
      .join('');
    const count = ['', '', 'Both', 'All three', 'All four', 'All five', 'All six', 'All seven', 'All eight'][family.games.length];
    return `<section class="family">
<div class="side"><h2>${escapeHtml(family.console)}</h2><p>${escapeHtml(family.blurb)}</p></div>
<div class="main">${regions}
<p class="together">${linkIcon}<span>${escapeHtml(family.together ?? `${count ?? `All ${family.games.length}`} trade and battle`)}</span></p></div></section>`;
  }).join('');

  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Games - ${appName}</title>
<meta name="description" content="The games you can play together in ${appName}: see each other on the map, trade and battle.">
<style>
:root{color-scheme:light dark;--bg:#f6f5f2;--fg:#1d1c1a;--muted:#6b6862;--line:#e3e1dc;--accent:#3b5bdb;--link:#c2410c;--card:#fff}
@media (prefers-color-scheme:dark){:root{--bg:#141413;--fg:#f2f1ee;--muted:#a3a09a;--line:#2e2d2a;--accent:#91a7ff;--link:#fb923c;--card:#1f1e1c}}
*{box-sizing:border-box}
body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--fg);line-height:1.5}
.wrap{max-width:1040px;margin:0 auto;padding:40px 16px 56px}
.back{color:var(--accent);text-decoration:none;font-size:14px}
header h1{font-size:clamp(28px,5vw,40px);line-height:1.15;margin:16px 0 6px;letter-spacing:-.01em}
header p{color:var(--muted);margin:0;max-width:64ch}
.rules{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:12px;margin:28px 0 8px;padding:0;list-style:none}
.rules li{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:14px 16px}
.rules b{display:block;margin-bottom:2px}.rules span{color:var(--muted);font-size:14px}
.family{display:grid;grid-template-columns:240px 1fr;gap:28px;border-top:1px solid var(--line);margin-top:32px;padding-top:28px}
.side h2{margin:0 0 8px;font-size:20px}.side p{margin:0;color:var(--muted);font-size:15px}
.tiles{display:grid;grid-template-columns:repeat(auto-fill,minmax(128px,1fr));gap:16px;margin:0;padding:0;list-style:none}
.tile b{display:block;margin-top:8px}.tile small{display:block;color:var(--muted);font-size:13px;line-height:1.35}
.art{position:relative;aspect-ratio:1;border-radius:14px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.18)}
.art svg,.art img{display:block;width:100%;height:100%;object-fit:cover}
.open{position:absolute;right:8px;bottom:8px;background:rgba(20,20,20,.78);color:#fff;font-size:12px;font-weight:600;border-radius:999px;padding:3px 9px}
.open i{display:inline-block;width:7px;height:7px;border-radius:50%;background:#22c55e;margin-right:5px;vertical-align:1px}
.region{display:flex;align-items:center;gap:10px;margin:14px 0 22px;color:var(--muted);font-size:13px;font-weight:600}
.region::before,.region::after{content:"";flex:1;height:1px;background:var(--line)}
.together{display:flex;align-items:center;gap:10px;margin:-8px 0 0;color:var(--link);font-size:14px;font-weight:600}
.together::before,.together::after{content:"";flex:1;border-top:2px dashed currentColor;opacity:.55}
.together svg{width:18px;height:18px;flex:none}
.how{margin-top:40px;background:var(--card);border:1px solid var(--line);border-radius:16px;padding:20px}
.how h2{margin:0 0 10px;font-size:20px}.how ol{margin:0;padding-left:20px}.how li{margin:6px 0}
.note{color:var(--muted);font-size:14px;margin-top:28px}
@media (max-width:700px){.family{grid-template-columns:1fr;gap:16px}.tiles{grid-template-columns:repeat(3,1fr);gap:12px}}
</style></head>
<body><div class="wrap">
<a class="back" href="/">&larr; ${appName}</a>
<header><h1>Games</h1>
<p>Play with friends in the same world, then walk up to each other to trade or battle with each game's own trade and battle screens. Every game here has been tested trading and battling.</p></header>
<ul class="rules">
<li><b>Your own game</b><span>Each player needs their own copy of a supported game on their phone.</span></li>
<li><b>Same family</b><span>Games trade and battle with the games they link with on real hardware.</span></li>
<li><b>Anywhere</b><span>Over Wi-Fi or mobile data. A dropped connection picks up where it left off.</span></li>
</ul>
${families}
<section class="how"><h2>How to play</h2><ol>
<li>Add your game in ${appName}.</li>
<li>Open the <b>Online</b> tab, choose <b>Multiplayer</b>, create a room and send your friend the room code.</li>
<li>Find each other on the map: the room shows where everyone is.</li>
<li>Walk up to your friend, press <b>A</b> and pick <b>Trade</b> or <b>Battle</b>. They press <b>A</b> to accept and it starts right there.</li>
</ol></section>
<p class="note">Game Boy and Game Boy Color games can't trade or battle with Game Boy Advance games, as on the real consoles. More games are added here once trading and battling them is tested.</p>
</div></body></html>`;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const port = Number(process.env.PORT ?? 8080);
  const server = createRelayServer();
  const host = process.env.HOST || '0.0.0.0';
  server.listen(port, host).then((address) => {
    console.log(`GBA/GBC Emulator relay listening on port ${address.port}`);
  });
  const shutdown = () => server.close().then(() => process.exit(0));
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}
