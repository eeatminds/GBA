import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BROADCAST, EMPTY_ROOM_MS, RoomRegistry, cleanName, describeGroups, describePlayers, isValidCode } from '../src/rooms.js';

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

test('trade & battle rooms hold up to 8 players', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'poke', maxPlayers: 99 });
  assert.equal(room.maxPlayers, 8);
  for (let i = 1; i < 8; i++) registry.joinRoom(String(i), { code: room.code });
  assert.throws(() => registry.joinRoom('8', { code: room.code }), { code: 'room_full' });
});

test('link sizes follow the link type', () => {
  const registry = new RoomRegistry();
  assert.equal(registry.groupSize(registry.createRoom('h', { mode: 'poke' }).room), 4);
  assert.equal(registry.groupSize(registry.createRoom('h', { mode: 'rfu' }).room), 5);
  const { room: gb } = registry.createRoom('h', { system: 'gbc', mode: 'poke' });
  assert.equal(gb.system, 'gb');
  assert.equal(gb.mode, 'gb');
  assert.equal(registry.groupSize(gb), 2);
  // A GBA room can't pick the Game Boy cable.
  assert.equal(registry.createRoom('h', { system: 'gba', mode: 'gb' }).room.mode, 'auto');
});

test('unknown code and protocol mismatch are rejected', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { protocol: 'gpSP v1.0' });
  assert.throws(() => registry.joinRoom('x', { code: 'ZZZZZZ' }), { code: 'not_found' });
  assert.throws(() => registry.joinRoom('x', { code: room.code, protocol: 'gpSP v2.0' }), { code: 'protocol_mismatch' });
  assert.equal(registry.joinRoom('x', { code: room.code, protocol: 'gpSP v1.0' }).player.id, 1);
});

test('play together rooms close when the host leaves', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { kind: 'netplay', system: 'snes' });
  registry.joinRoom('a', { code: room.code });
  assert.equal(registry.leave(room, 1).closed, false);
  assert.equal(registry.leave(room, 0).closed, true);
  assert.equal(registry.rooms.size, 0);
});

test('trade & battle rooms stay open when the host leaves, and the next player owns them', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { name: 'Ash' });
  registry.joinRoom('a', { code: room.code, name: 'Misty' });
  registry.joinRoom('b', { code: room.code, name: 'Brock' });
  const result = registry.leave(room, 0);
  assert.equal(result.closed, false);
  assert.equal(result.ownerChanged, true);
  assert.equal(room.ownerId, 1);
  assert.equal(registry.publicRooms().length, 0);
  assert.equal(registry.joinRoom('c', { code: room.code }).player.id, 0);
  registry.leave(room, 0);
  registry.leave(room, 1);
  // Empty, but the code keeps working for a while.
  assert.equal(registry.leave(room, 2).closed, false);
  assert.equal(registry.rooms.size, 1);
  assert.deepEqual(registry.sweep(Date.now()), []);
  assert.deepEqual(registry.sweep(Date.now() + EMPTY_ROOM_MS), [room.code]);
  assert.equal(registry.rooms.size, 0);
});

test('the first player into an empty trade & battle room owns it', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { name: 'Ash' });
  registry.joinRoom('a', { code: room.code, name: 'Misty' });
  registry.leave(room, 0);
  registry.leave(room, 1);
  assert.equal(room.ownerId, 1);
  const { player, ownerChanged } = registry.joinRoom('b', { code: room.code, name: 'Brock' });
  assert.equal(player.id, 0);
  assert.equal(ownerChanged, true);
  assert.equal(room.ownerId, 0);
  assert.equal(room.emptySince, null);
  assert.deepEqual(registry.sweep(Date.now() + EMPTY_ROOM_MS), []);
});

test('a dropped player keeps their seat and link, and takes it back with their token', () => {
  let n = 0;
  const registry = new RoomRegistry({ token: () => `token${n++}` });
  const { room, player: host } = registry.createRoom('host', { name: 'Gold' });
  const { player: guest } = registry.joinRoom('guest', { code: room.code, name: 'Silver' });
  assert.notEqual(host.token, guest.token);

  assert.equal(registry.markAway(room, 1), true);
  assert.deepEqual(describePlayers(room), [{ id: 0, name: 'Gold' }, { id: 1, name: 'Silver', away: true }]);
  // Still linked, but nothing is sent while away.
  assert.equal(room.groups.length, 1);
  assert.deepEqual(registry.recipients(room, 0, BROADCAST), []);
  // A newcomer doesn't get the held seat.
  assert.equal(registry.joinRoom('third', { code: room.code }).player.id, 2);
  registry.leave(room, 2);

  assert.throws(() => registry.resume('again', { code: room.code, id: 1, token: 'wrong' }), { code: 'resume_failed' });
  assert.throws(() => registry.resume('again', { code: 'ZZZZZZ', id: 1, token: guest.token }), { code: 'resume_failed' });
  const resumed = registry.resume('again', { code: room.code, id: 1, token: guest.token });
  assert.equal(resumed.player.id, 1);
  assert.equal(resumed.previous, null);
  assert.equal(guest.away, false);
  assert.deepEqual(registry.recipients(room, 0, BROADCAST).map((p) => p.connection), ['again']);
});

test('play together rooms send to everyone except the sender', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { kind: 'netplay', system: 'n64' });
  registry.joinRoom('a', { code: room.code });
  registry.joinRoom('b', { code: room.code });
  assert.deepEqual(registry.recipients(room, 1, BROADCAST).map((p) => p.id).sort(), [0, 2]);
  assert.deepEqual(registry.recipients(room, 0, 2).map((p) => p.id), [2]);
  assert.deepEqual(registry.recipients(room, 0, 0), []);
  assert.deepEqual(registry.recipients(room, 0, 9), []);
});

test('two players are linked right away; a third joins unlinked', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'poke' });
  assert.equal(registry.joinRoom('a', { code: room.code }).groupsChanged, true);
  assert.deepEqual(describeGroups(room), [[{ id: 0, slot: 0 }, { id: 1, slot: 1 }]]);
  assert.equal(registry.joinRoom('b', { code: room.code }).groupsChanged, false);
  // Data only reaches the sender's link.
  assert.deepEqual(registry.recipients(room, 0, BROADCAST).map((p) => p.id), [1]);
  assert.deepEqual(registry.recipients(room, 2, BROADCAST), []);
  assert.deepEqual(registry.recipients(room, 0, 2), []);
});

test('link requests: ask, accept, join an existing link, unlink', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'poke' });
  registry.joinRoom('a', { code: room.code });
  registry.joinRoom('b', { code: room.code });
  registry.joinRoom('c', { code: room.code });

  // Player 2 asks player 0, who is linked with 1: player 2 joins that link in the next slot.
  registry.requestLink(room, 2, 0);
  assert.throws(() => registry.acceptLink(room, 1, 2), { code: 'not_found' });
  registry.acceptLink(room, 0, 2);
  assert.deepEqual(describeGroups(room), [[{ id: 0, slot: 0 }, { id: 1, slot: 1 }, { id: 2, slot: 2 }]]);
  assert.deepEqual(registry.recipients(room, 2, BROADCAST).map((p) => p.id).sort(), [0, 1]);

  // A request can be declined, and then can't be accepted.
  registry.requestLink(room, 3, 1);
  assert.equal(registry.declineLink(room, 1, 3), true);
  assert.throws(() => registry.acceptLink(room, 1, 3), { code: 'not_found' });

  // Already in the same link.
  assert.throws(() => registry.requestLink(room, 1, 0), { code: 'already_linked' });

  // Unplugging the leader gives the others new slots, starting at 0.
  assert.equal(registry.removeFromGroup(room, 0), true);
  assert.deepEqual(describeGroups(room), [[{ id: 1, slot: 0 }, { id: 2, slot: 1 }]]);
  assert.equal(registry.removeFromGroup(room, 1), true);
  assert.deepEqual(describeGroups(room), []);
  assert.equal(registry.removeFromGroup(room, 1), false);
});

test('a Game Boy link holds 2 players, and linked players are busy for others', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { system: 'gb' });
  registry.joinRoom('a', { code: room.code });
  registry.joinRoom('b', { code: room.code });
  registry.joinRoom('c', { code: room.code });
  assert.throws(() => registry.requestLink(room, 2, 0), { code: 'group_full' });
  registry.requestLink(room, 2, 3);
  registry.acceptLink(room, 3, 2);
  assert.throws(() => registry.requestLink(room, 2, 0), { code: 'busy' });
  assert.equal(room.groups.length, 2);
});

test('leaving drops a player from their link and their requests', () => {
  const registry = new RoomRegistry();
  const { room } = registry.createRoom('host', { mode: 'poke' });
  registry.joinRoom('a', { code: room.code });
  registry.joinRoom('b', { code: room.code });
  registry.requestLink(room, 2, 1);
  const result = registry.leave(room, 1);
  assert.equal(result.groupsChanged, true);
  assert.deepEqual(describeGroups(room), []);
  assert.equal(room.requests.size, 0);
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
