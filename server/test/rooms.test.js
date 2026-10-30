import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BROADCAST, RoomRegistry, cleanName, isValidCode } from '../src/rooms.js';

test('host gets client id 0 and joiners get the lowest free id', () => {
  const registry = new RoomRegistry();
  const { room, player: host } = registry.createRoom('host', { name: 'Ash', mode: 'poke', public: true });
  assert.equal(host.id, 0);
  assert.ok(isValidCode(room.code));

  const a = registry.joinRoom('a', { code: room.code.toLowerCase(), name: 'Misty' });
  const b = registry.joinRoom('b', { code: room.code, name: 'Brock' });
  assert.equal(a.player.id, 1);
  assert.equal(b.player.id, 2);

  registry.leave(room, 1);
  const c = registry.joinRoom('c', { code: room.code, name: 'Gary' });
  assert.equal(c.player.id, 1);
});

test('link cable rooms hold at most 4 players', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'poke', maxPlayers: 99 });
  assert.equal(room.maxPlayers, 4);
  registry.joinRoom('1', { code: room.code });
  registry.joinRoom('2', { code: room.code });
  registry.joinRoom('3', { code: room.code });
  assert.throws(() => registry.joinRoom('4', { code: room.code }), { code: 'room_full' });
});

test('wireless adapter rooms hold up to 5 players', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'rfu' });
  assert.equal(room.maxPlayers, 5);
});

test('unknown code and protocol mismatch are rejected', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { protocol: 'gpSP v1.0' });
  assert.throws(() => registry.joinRoom('x', { code: 'ZZZZZZ' }), { code: 'not_found' });
  assert.throws(() => registry.joinRoom('x', { code: room.code, protocol: 'gpSP v2.0' }), { code: 'protocol_mismatch' });
  assert.equal(registry.joinRoom('x', { code: room.code, protocol: 'gpSP v1.0' }).player.id, 1);
});

test('room closes when the host leaves', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', {});
  registry.joinRoom('a', { code: room.code });
  assert.deepEqual(registry.leave(room, 1), { closed: false, removed: true });
  assert.deepEqual(registry.leave(room, 0), { closed: true, removed: true });
  assert.equal(registry.rooms.size, 0);
});

test('broadcast goes to everyone except the sender, direct goes to one player', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'rfu' });
  registry.joinRoom('a', { code: room.code });
  registry.joinRoom('b', { code: room.code });
  assert.deepEqual(registry.recipients(room, 1, BROADCAST).map((p) => p.id).sort(), [0, 2]);
  assert.deepEqual(registry.recipients(room, 0, 2).map((p) => p.id), [2]);
  assert.deepEqual(registry.recipients(room, 0, 0), []);
  assert.deepEqual(registry.recipients(room, 0, 9), []);
});

test('only public rooms with free slots are listed', () => {
  const registry = new RoomRegistry();
  registry.createRoom('private', { public: false });
  const { room: full } = registry.createRoom('full', { public: true, maxPlayers: 2 });
  registry.joinRoom('a', { code: full.code });
  const { room: open } = registry.createRoom('open', { public: true, game: 'Fire Red' });
  const listed = registry.publicRooms();
  assert.equal(listed.length, 1);
  assert.equal(listed[0].code, open.code);
  assert.equal(listed[0].game, 'Fire Red');
});

test('names are cleaned and limited', () => {
  assert.equal(cleanName('  Ash\u0000‮ Ketchum <script>  ', 'x'), 'Ash Ketchum script');
  assert.equal(cleanName('🔥🔥', 'Player 1'), 'Player 1');
  assert.equal(cleanName('a'.repeat(50), 'x').length, 20);
  assert.equal(cleanName('Пётр', 'x'), 'Пётр');
});

test('play together rooms use the system player limit and keep core and delay', () => {
  const registry = new RoomRegistry();
  const { room: snes } = registry.createRoom('host', { kind: 'netplay', system: 'snes', core: 'snes9x', delay: 6, maxPlayers: 4 });
  assert.equal(snes.kind, 'netplay');
  assert.equal(snes.maxPlayers, 2);
  assert.equal(snes.core, 'snes9x');
  assert.equal(snes.delay, 6);

  const { room: n64 } = registry.createRoom('host', { kind: 'netplay', system: 'n64', core: 'mupen64plus_next_gles3' });
  assert.equal(n64.maxPlayers, 4);

  assert.throws(() => registry.createRoom('host', { kind: 'netplay', system: '3ds' }), { code: 'bad_request' });
  assert.throws(() => registry.createRoom('host', { kind: 'link', system: 'snes' }), { code: 'bad_request' });
});

test('core names and delays are sanitized', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { kind: 'netplay', system: 'nes', core: '../FCEUmm;rm', delay: 99 });
  assert.equal(room.core, 'fceummrm');
  assert.equal(room.delay, 15);
});
