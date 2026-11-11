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
  // Two players are linked right away.
  const groups = await host.next(ofType('groups'));
  assert.deepEqual(groups.groups, [[{ id: 0, slot: 0 }, { id: 1, slot: 1 }]]);
  await guest.next(ofType('groups'));

  guest.sendPacket(0xffff, [1, 2, 3]);
  const atHost = await host.next(binary);
  assert.equal(atHost.from, 1);
  assert.deepEqual([...atHost.payload], [1, 2, 3]);

  host.sendPacket(1, [9, 8]);
  const atGuest = await guest.next(binary);
  assert.equal(atGuest.from, 0);
  assert.deepEqual([...atGuest.payload], [9, 8]);

  // A trade & battle room stays open for the others when the host leaves.
  host.sendJson({ t: 'leave' });
  assert.equal((await guest.next(ofType('peer_left'))).id, 0);
  assert.deepEqual((await guest.next(ofType('groups'))).groups, []);
  assert.equal((await guest.next(ofType('owner'))).id, 1);
  guest.ws.close();
});

test('a dropped player is away, then takes their seat and link back', async () => {
  const host = connect();
  await host.opened;
  host.sendJson({ t: 'host', system: 'gbc', name: 'Gold' });
  const hosted = await host.next(ofType('hosted'));
  assert.equal(typeof hosted.token, 'string');
  const code = hosted.room.code;

  const guest = connect();
  await guest.opened;
  guest.sendJson({ t: 'join', code, name: 'Silver' });
  const joined = await guest.next(ofType('joined'));
  await host.next(ofType('groups'));

  // The guest's connection drops: they are away, still linked.
  guest.ws.terminate();
  assert.equal((await host.next(ofType('peer_away'))).id, 1);

  // A wrong token doesn't get the seat.
  const thief = connect();
  await thief.opened;
  thief.sendJson({ t: 'resume', code, id: 1, token: 'nope' });
  assert.equal((await thief.next(ofType('error'))).code, 'resume_failed');
  thief.ws.close();

  const back = connect();
  await back.opened;
  back.sendJson({ t: 'resume', code, id: 1, token: joined.token });
  const resumed = await back.next(ofType('joined'));
  assert.equal(resumed.clientId, 1);
  assert.equal(resumed.resumed, true);
  assert.deepEqual(resumed.groups, [[{ id: 0, slot: 0 }, { id: 1, slot: 1 }]]);
  assert.equal((await host.next(ofType('peer_back'))).id, 1);

  host.sendPacket(1, [4, 2]);
  assert.deepEqual([...(await back.next(binary)).payload], [4, 2]);

  // The creator steps out for good: the room stays open for the guest.
  host.sendJson({ t: 'leave' });
  assert.equal((await back.next(ofType('peer_left'))).id, 0);
  assert.equal((await back.next(ofType('owner'))).id, 1);
  back.sendJson({ t: 'leave' });
  await back.closed;
  // Empty now, but the code still works.
  const room = await (await fetch(`http://${base}/api/rooms/${code}`)).json();
  assert.equal(room.room.players, 0);
});

test('a seat is given up when its player does not come back in time', async () => {
  const quick = createRelayServer({ log: () => {}, resumeGraceMs: 150 });
  const address = await quick.listen(0, '127.0.0.1');
  const open = () => {
    const ws = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
    const inbox = [];
    ws.on('message', (data) => inbox.push(JSON.parse(data.toString())));
    return { ws, inbox, opened: new Promise((resolve) => ws.on('open', resolve)) };
  };
  const waitFor = async (inbox, t) => {
    for (let i = 0; i < 100; i++) {
      const found = inbox.find((m) => m.t === t);
      if (found) return found;
      await new Promise((r) => setTimeout(r, 20));
    }
    throw new Error(`no ${t}`);
  };
  const a = open();
  await a.opened;
  a.ws.send(JSON.stringify({ t: 'host', system: 'gba' }));
  const { room } = await waitFor(a.inbox, 'hosted');
  const b = open();
  await b.opened;
  b.ws.send(JSON.stringify({ t: 'join', code: room.code }));
  await waitFor(b.inbox, 'joined');
  b.ws.terminate();
  assert.equal((await waitFor(a.inbox, 'peer_away')).id, 1);
  assert.equal((await waitFor(a.inbox, 'peer_left')).id, 1);
  a.ws.close();
  await quick.close();
});

test('talking to a player: trade or battle, yes links the two, no just answers', async () => {
  const players = [connect(), connect(), connect()];
  await Promise.all(players.map((p) => p.opened));
  const [a, b, c] = players;
  a.sendJson({ t: 'host', system: 'gbc', name: 'Gold' });
  const { room } = await a.next(ofType('hosted'));
  b.sendJson({ t: 'join', code: room.code, name: 'Silver' });
  await b.next(ofType('joined'));
  await a.next(ofType('groups'));
  await b.next(ofType('groups'));
  c.sendJson({ t: 'join', code: room.code, name: 'Kris' });
  await c.next(ofType('joined'));

  // Kris talks to Gold, who is on a full Game Boy link with Silver.
  c.sendJson({ t: 'invite', id: 0, kind: 'battle' });
  assert.deepEqual(await a.next(ofType('invite')), { t: 'invite', from: 2, name: 'Kris', kind: 'battle' });
  a.sendJson({ t: 'invite_reply', id: 2, accept: true });
  assert.deepEqual(await c.next(ofType('invite_reply')), { t: 'invite_reply', from: 0, accept: true, kind: 'battle' });
  // Gold and Kris are linked now; Silver was unplugged.
  assert.deepEqual((await b.next(ofType('groups'))).groups, [[{ id: 2, slot: 0 }, { id: 0, slot: 1 }]]);

  // Silver asks Kris to trade; Kris says no. Nothing changes.
  b.sendJson({ t: 'invite', id: 2, kind: 'anything' });
  assert.equal((await c.next(ofType('invite'))).kind, 'trade');
  c.sendJson({ t: 'invite_reply', id: 1, accept: false });
  assert.deepEqual(await b.next(ofType('invite_reply')), { t: 'invite_reply', from: 2, accept: false, kind: 'trade' });

  // An answer without a question does nothing.
  b.sendJson({ t: 'invite_reply', id: 0, accept: true });
  b.sendJson({ t: 'ping' });
  await b.next(ofType('pong'));
  players.forEach((p) => p.ws.close());
});

test('a new name from the game goes to everyone else, and to players who join later', async () => {
  const players = [connect(), connect(), connect()];
  await Promise.all(players.map((p) => p.opened));
  const [a, b, c] = players;
  a.sendJson({ t: 'host', system: 'gbc' });
  const { room } = await a.next(ofType('hosted'));
  b.sendJson({ t: 'join', code: room.code });
  await b.next(ofType('joined'));

  b.sendJson({ t: 'name', name: '  SILVER<>  ' });
  assert.deepEqual(await a.next(ofType('renamed')), { t: 'renamed', id: 1, name: 'SILVER' });
  // The same name again, or an empty one, changes nothing.
  b.sendJson({ t: 'name', name: 'SILVER' });
  b.sendJson({ t: 'name', name: '<>' });
  c.sendJson({ t: 'join', code: room.code });
  const joined = await c.next(ofType('joined'));
  assert.equal(joined.players.find((p) => p.id === 1).name, 'SILVER');
  a.sendJson({ t: 'name', name: 'GOLD' });
  assert.deepEqual(await c.next(ofType('renamed')), { t: 'renamed', id: 0, name: 'GOLD' });
  assert.deepEqual(await b.next(ofType('renamed')), { t: 'renamed', id: 0, name: 'GOLD' });
  players.forEach((p) => p.ws.close());
});

test('play together room closes when the host leaves', async () => {
  const host = connect();
  await host.opened;
  host.sendJson({ t: 'host', kind: 'netplay', system: 'snes', core: 'snes9x' });
  const { room } = await host.next(ofType('hosted'));
  const guest = connect();
  await guest.opened;
  guest.sendJson({ t: 'join', code: room.code });
  await guest.next(ofType('joined'));
  host.ws.close();
  assert.equal((await guest.next(ofType('closed'))).reason, 'host_left');
  assert.equal(await guest.closed, 4000);
});

test('players ask to link, accept, decline and unlink', async () => {
  const players = [connect(), connect(), connect()];
  await Promise.all(players.map((p) => p.opened));
  const [a, b, c] = players;
  a.sendJson({ t: 'host', system: 'gbc', name: 'Gold' });
  const { room } = await a.next(ofType('hosted'));
  assert.equal(room.system, 'gb');
  assert.equal(room.maxPlayers, 8);
  b.sendJson({ t: 'join', code: room.code, name: 'Silver' });
  await b.next(ofType('joined'));
  await a.next(ofType('groups'));
  await b.next(ofType('groups'));
  c.sendJson({ t: 'join', code: room.code, name: 'Kris' });
  const joined = await c.next(ofType('joined'));
  assert.deepEqual(joined.groups, [[{ id: 0, slot: 0 }, { id: 1, slot: 1 }]]);
  assert.equal(joined.ownerId, 0);

  // Kris isn't linked, so Gold's data doesn't reach Kris.
  a.sendPacket(0xffff, [7]);
  assert.equal((await b.next(binary)).from, 0);

  // Asking a player who is already on a full Game Boy link fails without ending the game.
  c.sendJson({ t: 'link_request', id: 0 });
  assert.equal((await c.next(ofType('link_error'))).code, 'group_full');

  // Silver unplugs, Kris asks Gold, Gold says yes.
  b.sendJson({ t: 'unlink' });
  assert.deepEqual((await a.next(ofType('groups'))).groups, []);
  assert.deepEqual((await c.next(ofType('groups'))).groups, []);
  c.sendJson({ t: 'link_request', id: 0 });
  const request = await a.next(ofType('link_request'));
  assert.deepEqual(request, { t: 'link_request', from: 2, name: 'Kris' });
  a.sendJson({ t: 'link_accept', id: 2 });
  assert.deepEqual((await c.next(ofType('groups'))).groups, [[{ id: 0, slot: 0 }, { id: 2, slot: 1 }]]);
  c.sendPacket(0, [5, 6]);
  const atGold = await a.next(binary);
  assert.equal(atGold.from, 2);
  assert.deepEqual([...atGold.payload], [5, 6]);

  // Silver asks Kris, Kris says no.
  b.sendJson({ t: 'link_request', id: 2 });
  assert.equal((await b.next(ofType('link_error'))).code, 'group_full');
  b.sendJson({ t: 'link_request', id: 0 });
  await b.next(ofType('link_error'));

  players.forEach((p) => p.ws.close());
});

test('positions in the world go to everyone else in the room', async () => {
  const players = [connect(), connect(), connect()];
  await Promise.all(players.map((p) => p.opened));
  const [a, b, c] = players;
  a.sendJson({ t: 'host', system: 'gba', name: 'May' });
  const { room } = await a.next(ofType('hosted'));
  b.sendJson({ t: 'join', code: room.code });
  await b.next(ofType('joined'));
  c.sendJson({ t: 'join', code: room.code });
  await c.next(ofType('joined'));

  a.sendJson({ t: 'pos', w: 'hoenn-e', m: '0.9', x: 160, y: 96, s: 89, f: 3, h: 1, extra: 'dropped' });
  for (const p of [b, c]) {
    assert.deepEqual(await p.next(ofType('pos')), { t: 'pos', from: 0, w: 'hoenn-e', m: '0.9', x: 160, y: 96, s: 89, f: 3, h: 1 });
  }
  // Hidden (not walking around): just the world.
  a.sendJson({ t: 'pos', w: 'hoenn-e' });
  assert.deepEqual(await b.next(ofType('pos')), { t: 'pos', from: 0, w: 'hoenn-e' });
  // Bad values are dropped.
  a.sendJson({ t: 'pos', w: 'hoenn-e', m: '<script>', x: 1, y: 1, s: 1, f: 1, h: 0 });
  a.sendJson({ t: 'pos', w: 'hoenn-e', m: '0.1', x: 'far', y: 1, s: 1, f: 1, h: 0 });
  a.sendJson({ t: 'pos', w: 'hoenn-e', m: '0.2', x: 2, y: 2, s: 1, f: 1, h: 0 });
  assert.equal((await b.next(ofType('pos'))).m, '0.2');
  players.forEach((p) => p.ws.close());
});

test('the room owner can remove a player', async () => {
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

test('link reports are kept by room code and can be read back', async () => {
  const empty = await (await fetch(`http://${base}/api/linklog/ABCDEF`)).text();
  assert.equal(empty, 'No link reports for this room.');
  const post = (body) =>
    fetch(`http://${base}/api/linklog`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  assert.equal((await post({ code: 'abcdef', id: 1, name: 'Gold', reason: 'FAILED: our script never ran', text: 'line one\nline two' })).status, 204);
  assert.equal((await post({ code: 'ABCDEF', id: 0, name: 'Silver', reason: 'done', text: 'ok' })).status, 204);
  assert.equal((await post({ code: 'nope', text: 'x' })).status, 400);
  const text = await (await fetch(`http://${base}/api/linklog/ABCDEF`)).text();
  assert.ok(text.includes('player 1 (Gold): FAILED: our script never ran\nline one\nline two'));
  assert.ok(text.includes('player 0 (Silver): done\nok'));
});

test('the games page lists the games players can trade and battle in, and the home page links to it', async () => {
  const page = await fetch(`http://${base}/games`);
  assert.equal(page.status, 200);
  const html = await page.text();
  for (const name of ['Gold', 'Silver', 'Crystal', 'trade and battle', 'See each other in Johto', 'Same World']) assert.ok(html.includes(name), name);
  for (const name of ['FireRed', 'LeafGreen', 'See each other in Kanto', 'Both trade and battle']) assert.ok(html.includes(name), name);
  assert.ok(!html.includes('<script'));
  const home = await (await fetch(`http://${base}/`)).text();
  assert.ok(home.includes('href="/games"'));
});

test('the games page counts open rooms per game and shows a cover the owner added', async () => {
  const { mkdtempSync, writeFileSync } = await import('node:fs');
  const { join } = await import('node:path');
  const { tmpdir } = await import('node:os');
  const dir = mkdtempSync(join(tmpdir(), 'covers-'));
  writeFileSync(join(dir, 'crystal.png'), Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  const own = createRelayServer({ log: () => {}, coversDir: dir });
  const address = await own.listen(0, '127.0.0.1');
  const url = `http://127.0.0.1:${address.port}`;
  const host = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  await new Promise((resolve) => host.on('open', resolve));
  host.send(JSON.stringify({ t: 'host', kind: 'link', system: 'gbc', game: 'Pokemon - Crystal Version (USA, Europe) (Rev 1)', name: 'Kris' }));
  await new Promise((resolve) => host.on('message', resolve));

  // A LeafGreen room (GBA): counted for LeafGreen only.
  const guest = new WebSocket(`ws://127.0.0.1:${address.port}/ws`);
  await new Promise((resolve) => guest.on('open', resolve));
  guest.send(JSON.stringify({ t: 'host', kind: 'link', system: 'gba', game: 'Pokemon - Leaf Green Version (U) (V1.1)', name: 'Leaf' }));
  await new Promise((resolve) => guest.on('message', resolve));

  const html = await (await fetch(`${url}/games`)).text();
  assert.ok(html.includes('<img src="/covers/crystal.png"'));
  const tiles = Object.fromEntries(
    html.split('<li class="tile">').slice(1).map((tile) => [/<b>([^<]+)<\/b>/.exec(tile)[1], tile.includes('1 open')]),
  );
  assert.deepEqual(tiles, { Gold: false, Silver: false, Crystal: true, FireRed: false, LeafGreen: true });
  guest.close();
  const cover = await fetch(`${url}/covers/crystal.png`);
  assert.equal(cover.status, 200);
  assert.equal(cover.headers.get('content-type'), 'image/png');
  assert.equal((await fetch(`${url}/covers/gold.png`)).status, 404);
  assert.equal((await fetch(`${url}/covers/..%2Fpasswd`)).status, 404);
  host.close();
  await own.close();
});
