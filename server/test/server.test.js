import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import WebSocket from 'ws';
import { createRelayServer } from '../src/server.js';

let server;
let base;

before(async () => {
  server = createRelayServer({ log: () => {}, androidPackage: 'com.example.app', androidCertSha256: 'AA:BB' });
  const address = await server.listen(0, '127.0.0.1');
  base = `127.0.0.1:${address.port}`;
});

after(async () => {
  await server.close();
});

// Opens a socket and collects every message so tests can await specific ones.
function connect() {
  const ws = new WebSocket(`ws://${base}/ws`);
  const inbox = [];
  const waiters = [];
  ws.on('message', (data, isBinary) => {
    const message = isBinary ? { binary: true, from: data.readUInt16BE(0), payload: data.subarray(2) } : JSON.parse(data.toString());
    const index = waiters.findIndex((w) => w.match(message));
    if (index >= 0) waiters.splice(index, 1)[0].resolve(message);
    else inbox.push(message);
  });
  const closed = new Promise((resolve) => ws.on('close', (code) => resolve(code)));
  const opened = new Promise((resolve, reject) => {
    ws.on('open', resolve);
    ws.on('error', reject);
  });
  return {
    ws,
    opened,
    closed,
    sendJson: (m) => ws.send(JSON.stringify(m)),
    sendPacket: (target, bytes) => {
      const buf = Buffer.alloc(2 + bytes.length);
      buf.writeUInt16BE(target, 0);
      Buffer.from(bytes).copy(buf, 2);
      ws.send(buf);
    },
    next(match) {
      const index = inbox.findIndex(match);
      if (index >= 0) return Promise.resolve(inbox.splice(index, 1)[0]);
      return new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('timed out waiting for message')), 2000);
        waiters.push({ match, resolve: (m) => { clearTimeout(timer); resolve(m); } });
      });
    },
  };
}

const ofType = (t) => (m) => m.t === t;
const binary = (m) => m.binary === true;

test('host, join, relay packets and close room', async () => {
  const host = connect();
  await host.opened;
  host.sendJson({ t: 'host', name: 'Ash', game: 'Fire Red', mode: 'rfu', public: true, protocol: 'gpSP v1.0' });
  const hosted = await host.next(ofType('hosted'));
  assert.equal(hosted.clientId, 0);
  const code = hosted.room.code;

  const list = await (await fetch(`http://${base}/api/rooms`)).json();
  assert.ok(list.rooms.some((r) => r.code === code && r.game === 'Fire Red'));

  const guest = connect();
  await guest.opened;
  guest.sendJson({ t: 'join', code, name: 'Misty', protocol: 'gpSP v1.0' });
  const joined = await guest.next(ofType('joined'));
  assert.equal(joined.clientId, 1);
  assert.deepEqual(joined.players.map((p) => p.name), ['Ash', 'Misty']);
  const peer = await host.next(ofType('peer_joined'));
  assert.deepEqual(peer, { t: 'peer_joined', id: 1, name: 'Misty' });

  guest.sendPacket(0xffff, [1, 2, 3]);
  const atHost = await host.next(binary);
  assert.equal(atHost.from, 1);
  assert.deepEqual([...atHost.payload], [1, 2, 3]);

  host.sendPacket(1, [9, 8]);
  const atGuest = await guest.next(binary);
  assert.equal(atGuest.from, 0);
  assert.deepEqual([...atGuest.payload], [9, 8]);

  host.ws.close();
  const closedMessage = await guest.next(ofType('closed'));
  assert.equal(closedMessage.reason, 'host_left');
  assert.equal(await guest.closed, 4000);
});

test('host can kick a player the emulator refused', async () => {
  const host = connect();
  await host.opened;
  host.sendJson({ t: 'host', mode: 'poke' });
  const { room } = await host.next(ofType('hosted'));

  const guest = connect();
  await guest.opened;
  guest.sendJson({ t: 'join', code: room.code });
  await guest.next(ofType('joined'));
  await host.next(ofType('peer_joined'));

  host.sendJson({ t: 'kick', id: 1, reason: 'full' });
  assert.equal((await guest.next(ofType('kicked'))).reason, 'full');
  assert.equal(await guest.closed, 4001);
  assert.equal((await host.next(ofType('peer_left'))).id, 1);
  host.ws.close();
});

test('joining a missing room returns an error', async () => {
  const guest = connect();
  await guest.opened;
  guest.sendJson({ t: 'join', code: 'ABCDEF' });
  const error = await guest.next(ofType('error'));
  assert.equal(error.code, 'not_found');
  guest.ws.close();
});

test('invite page and app links file are served', async () => {
  const page = await fetch(`http://${base}/r/ABCDEF`);
  assert.equal(page.status, 200);
  const html = await page.text();
  assert.ok(html.includes('retrolink://join/ABCDEF'));

  const bad = await fetch(`http://${base}/r/<script>`);
  assert.equal(bad.status, 404);
  assert.ok(!(await bad.text()).includes('<script>'));

  const links = await (await fetch(`http://${base}/.well-known/assetlinks.json`)).json();
  assert.equal(links[0].target.package_name, 'com.example.app');
});
