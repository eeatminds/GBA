// Room bookkeeping for the GBA/GBC Emulator relay. Pure logic, no networking, so it is easy to test.

import { randomBytes } from 'node:crypto';

export const BROADCAST = 0xffff;

// A trade & battle room with nobody in it stays open this long, so its code keeps working
// (the creator can come back, friends with the invite can still join).
export const EMPTY_ROOM_MS = 5 * 60_000;

// Trade & battle rooms hold up to 8 players. Inside a room, players link up in small
// groups, like plugging in link cables: only players in the same group exchange data.
export const LINK_ROOM_CAPACITY = 8;

// Link group sizes come from the emulators: gpSP links up to 4 GBAs (5 with the wireless
// adapter), and a Game Boy link cable connects 2.
export const MODES = {
  auto: { label: 'Automatic', groupSize: 4, family: 'gba' },
  poke: { label: 'Link cable (Pokemon Gen 3)', groupSize: 4, family: 'gba' },
  rfu: { label: 'Wireless adapter', groupSize: 5, family: 'gba' },
  aw1: { label: 'Link cable (Advance Wars)', groupSize: 4, family: 'gba' },
  aw2: { label: 'Link cable (Advance Wars 2)', groupSize: 4, family: 'gba' },
  gb: { label: 'Link cable (Game Boy)', groupSize: 2, family: 'gb' },
};

// Game Boy and Game Boy Color games share rooms, because their games link with each other.
const LINK_FAMILIES = { gba: 'gba', gb: 'gb', gbc: 'gb' };

// "link" rooms connect separate consoles (trading, battles). "netplay" rooms run one game
// for everyone, with inputs synced between players.
export const KINDS = new Set(['link', 'netplay']);

// Systems that support "play together" rooms, with their player limit.
export const NETPLAY_SYSTEMS = {
  nes: 2,
  snes: 2,
  md: 2,
  sms: 2,
  gg: 2,
  scd: 2,
  pce: 2,
  atari2600: 2,
  atari7800: 2,
  psx: 2,
  n64: 4,
  fbneo: 4,
  mame2003plus: 4,
};

// No 0/O/1/I/L so codes are easy to read out loud.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
export const CODE_LENGTH = 6;

const MAX_NAME_LENGTH = 20;
const MAX_GAME_LENGTH = 60;

export class RoomError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}

// Keeps letters, digits, spaces and a few symbols from any script. Drops control
// characters, emoji and anything else that could be used to fake UI or spam.
export function cleanText(value, maxLength) {
  if (typeof value !== 'string') return '';
  const cleaned = value
    .normalize('NFKC')
    .replace(/[^\p{L}\p{N} '._\-!?&:()]/gu, '')
    .replace(/\s+/g, ' ')
    .trim();
  return [...cleaned].slice(0, maxLength).join('').trim();
}

export function cleanName(value, fallback) {
  return cleanText(value, MAX_NAME_LENGTH) || fallback;
}

export function isValidCode(code) {
  return typeof code === 'string' && code.length === CODE_LENGTH && [...code].every((c) => CODE_ALPHABET.includes(c));
}

export function normalizeCode(code) {
  return typeof code === 'string' ? code.trim().toUpperCase() : '';
}

export class RoomRegistry {
  constructor({ maxRooms = 5000, random = Math.random, token = () => randomBytes(16).toString('hex'), emptyRoomMs = EMPTY_ROOM_MS } = {}) {
    this.maxRooms = maxRooms;
    this.random = random;
    this.token = token;
    this.emptyRoomMs = emptyRoomMs;
    this.rooms = new Map();
  }

  newCode() {
    for (let attempt = 0; attempt < 100; attempt++) {
      let code = '';
      for (let i = 0; i < CODE_LENGTH; i++) {
        code += CODE_ALPHABET[Math.floor(this.random() * CODE_ALPHABET.length)];
      }
      if (!this.rooms.has(code)) return code;
    }
    throw new RoomError('server_full', 'Could not create a room code. Try again.');
  }

  // `connection` is any object; the registry only stores it and hands it back.
  createRoom(connection, request) {
    if (this.rooms.size >= this.maxRooms) {
      throw new RoomError('server_full', 'The server is full right now. Try again later.');
    }
    const kind = KINDS.has(request.kind) ? request.kind : 'link';
    let system = typeof request.system === 'string' ? request.system : 'gba';
    const requestedMax = Number.isInteger(request.maxPlayers) ? request.maxPlayers : undefined;

    let modeKey = 'auto';
    let capacity;
    if (kind === 'link') {
      const family = LINK_FAMILIES[system];
      if (!family) throw new RoomError('bad_request', 'This system does not support online rooms yet.');
      system = family;
      if (family === 'gb') {
        modeKey = 'gb';
      } else {
        const requested = typeof request.mode === 'string' ? MODES[request.mode] : undefined;
        modeKey = requested && requested.family === 'gba' ? request.mode : 'auto';
      }
      capacity = LINK_ROOM_CAPACITY;
    } else {
      capacity = NETPLAY_SYSTEMS[system];
      if (!capacity) throw new RoomError('bad_request', 'This system does not support online rooms yet.');
    }
    const maxPlayers = Math.max(2, Math.min(capacity, requestedMax ?? capacity));
    const core = typeof request.core === 'string' ? request.core.toLowerCase().replace(/[^a-z0-9_]/g, '').slice(0, 40) : '';
    const delay = Number.isInteger(request.delay) ? Math.max(1, Math.min(15, request.delay)) : 4;

    const code = this.newCode();
    // The token lets a player take their seat back after losing their connection.
    const host = { id: 0, name: cleanName(request.name, 'Player 1'), connection, token: this.token(), away: false };
    const room = {
      code,
      kind,
      system,
      core,
      delay,
      mode: modeKey,
      game: cleanText(request.game, MAX_GAME_LENGTH) || 'Game',
      isPublic: request.public === true,
      protocol: typeof request.protocol === 'string' ? request.protocol.slice(0, 64) : '',
      maxPlayers,
      createdAt: Date.now(),
      ownerId: 0,
      players: new Map([[0, host]]),
      // Each group: [{ id, slot }]. The slot is the player's place on the link cable;
      // the player in slot 0 leads the group.
      groups: [],
      // Pending link requests, as "from>to".
      requests: new Set(),
      // Pending invitations to trade or battle, "from>to" -> kind.
      invites: new Map(),
      // When the last player left (trade & battle rooms stay open a while after that).
      emptySince: null,
    };
    this.rooms.set(code, room);
    return { room, player: host };
  }

  joinRoom(connection, request) {
    const code = normalizeCode(request.code);
    const room = isValidCode(code) ? this.rooms.get(code) : undefined;
    if (!room) throw new RoomError('not_found', 'Room not found. Check the code and try again.');

    if (room.protocol && typeof request.protocol === 'string' && request.protocol !== room.protocol) {
      throw new RoomError('protocol_mismatch', 'The host is using a different app version. Both players need the latest update.');
    }

    // Netplay rooms keep id 0 for the host. Link rooms can reuse any free id, since the
    // room stays open when its first player leaves.
    let id = -1;
    for (let candidate = room.kind === 'link' ? 0 : 1; candidate < room.maxPlayers; candidate++) {
      if (!room.players.has(candidate)) {
        id = candidate;
        break;
      }
    }
    if (id < 0) throw new RoomError('room_full', 'This room is full.');

    const player = { id, name: cleanName(request.name, `Player ${id + 1}`), connection, token: this.token(), away: false };
    room.players.set(id, player);
    room.emptySince = null;
    // Joining a room nobody owns any more (everyone left): the new player owns it.
    let ownerChanged = false;
    if (!room.players.has(room.ownerId)) {
      room.ownerId = id;
      ownerChanged = true;
    }

    // With just two players there is only one sensible link: connect them right away.
    let groupsChanged = false;
    if (room.kind === 'link' && room.players.size === 2 && room.groups.length === 0) {
      const other = [...room.players.keys()].find((pid) => pid !== id);
      room.groups.push([
        { id: other, slot: 0 },
        { id, slot: 1 },
      ]);
      groupsChanged = true;
    }
    return { room, player, groupsChanged, ownerChanged };
  }

  // A player lost their connection without leaving. They keep their seat and their link
  // while they come back (see `resume`); nothing is sent to them in the meantime.
  markAway(room, playerId) {
    const player = room.players.get(playerId);
    if (!player || !this.rooms.has(room.code)) return false;
    player.away = true;
    player.connection = null;
    return true;
  }

  // A player who lost their connection takes their seat back with the token they were given.
  // Returns { room, player, previous } where `previous` is the old connection, if any.
  resume(connection, request) {
    const code = normalizeCode(request.code);
    const room = isValidCode(code) ? this.rooms.get(code) : undefined;
    const player = room?.players.get(request.id);
    if (!room || !player || typeof request.token !== 'string' || request.token !== player.token) {
      throw new RoomError('resume_failed', 'Your place in the room is gone.');
    }
    if (room.protocol && typeof request.protocol === 'string' && request.protocol !== room.protocol) {
      throw new RoomError('protocol_mismatch', 'The host is using a different app version. Both players need the latest update.');
    }
    const previous = player.connection;
    player.connection = connection;
    player.away = false;
    return { room, player, previous };
  }

  // Returns { closed, removed, groupsChanged, ownerChanged }. A netplay room closes when its
  // host leaves. A link room stays open, even empty for a while (see `sweep`).
  leave(room, playerId) {
    if (!this.rooms.has(room.code) || !room.players.has(playerId)) {
      return { closed: false, removed: false, groupsChanged: false, ownerChanged: false };
    }
    room.players.delete(playerId);
    for (const request of [...room.requests]) {
      const [from, to] = request.split('>').map(Number);
      if (from === playerId || to === playerId) room.requests.delete(request);
    }
    for (const invite of [...room.invites.keys()]) {
      const [from, to] = invite.split('>').map(Number);
      if (from === playerId || to === playerId) room.invites.delete(invite);
    }
    const groupsChanged = this.removeFromGroup(room, playerId);

    if (room.kind !== 'link' && (playerId === 0 || room.players.size === 0)) {
      this.rooms.delete(room.code);
      return { closed: true, removed: true, groupsChanged, ownerChanged: false };
    }
    if (room.players.size === 0) {
      room.emptySince = Date.now();
      return { closed: false, removed: true, groupsChanged, ownerChanged: false };
    }
    let ownerChanged = false;
    if (room.ownerId === playerId) {
      room.ownerId = Math.min(...room.players.keys());
      ownerChanged = true;
    }
    return { closed: false, removed: true, groupsChanged, ownerChanged };
  }

  // Closes trade & battle rooms that have been empty for a while. Returns their codes.
  sweep(now = Date.now()) {
    const closed = [];
    for (const room of this.rooms.values()) {
      if (room.players.size === 0 && room.emptySince !== null && now - room.emptySince >= this.emptyRoomMs) {
        this.rooms.delete(room.code);
        closed.push(room.code);
      }
    }
    return closed;
  }

  groupSize(room) {
    return MODES[room.mode]?.groupSize ?? 2;
  }

  groupOf(room, playerId) {
    return room.groups.find((group) => group.some((member) => member.id === playerId));
  }

  // Checks that `fromId` linking with `toId` would make a valid group, and returns
  // the group each one is in now.
  checkLink(room, fromId, toId) {
    if (room.kind !== 'link') throw new RoomError('bad_request', 'Players in this room are always connected.');
    if (fromId === toId || !room.players.has(toId) || !room.players.has(fromId)) {
      throw new RoomError('not_found', 'That player is not in the room any more.');
    }
    const fromGroup = this.groupOf(room, fromId);
    const toGroup = this.groupOf(room, toId);
    if (fromGroup && fromGroup === toGroup) throw new RoomError('already_linked', 'You are already linked.');
    if (fromGroup && toGroup) throw new RoomError('busy', 'Both of you are linked with other players. Unlink first.');
    const joined = fromGroup ?? toGroup;
    if (joined && joined.length >= this.groupSize(room)) {
      throw new RoomError('group_full', 'That link is full.');
    }
    return { fromGroup, toGroup };
  }

  requestLink(room, fromId, toId) {
    this.checkLink(room, fromId, toId);
    // One pending request per player at a time.
    for (const request of [...room.requests]) {
      if (request.startsWith(`${fromId}>`)) room.requests.delete(request);
    }
    room.requests.add(`${fromId}>${toId}`);
  }

  // The player who was asked (`accepterId`) says yes.
  acceptLink(room, accepterId, requesterId) {
    const key = `${requesterId}>${accepterId}`;
    if (!room.requests.has(key)) throw new RoomError('not_found', 'That request is no longer open.');
    room.requests.delete(key);
    const { fromGroup, toGroup } = this.checkLink(room, requesterId, accepterId);
    if (toGroup) {
      toGroup.push({ id: requesterId, slot: freeSlot(toGroup) });
    } else if (fromGroup) {
      fromGroup.push({ id: accepterId, slot: freeSlot(fromGroup) });
    } else {
      room.groups.push([
        { id: accepterId, slot: 0 },
        { id: requesterId, slot: 1 },
      ]);
    }
  }

  // Player `fromId` asks `toId` to trade or battle (like talking to them in the game).
  invite(room, fromId, toId, kind) {
    if (room.kind !== 'link') throw new RoomError('bad_request', 'Players in this room are always connected.');
    if (fromId === toId || !room.players.has(toId)) throw new RoomError('not_found', 'That player is not in the room any more.');
    const cleanKind = kind === 'battle' ? 'battle' : 'trade';
    room.invites.set(`${fromId}>${toId}`, cleanKind);
    return cleanKind;
  }

  // The invited player answers. Returns { kind, groupsChanged }, or null when there was no
  // such invitation. Saying yes links the two of them.
  answerInvite(room, accepterId, inviterId, accept) {
    const key = `${inviterId}>${accepterId}`;
    const kind = room.invites.get(key);
    if (!kind) return null;
    room.invites.delete(key);
    let groupsChanged = false;
    if (accept && room.players.has(inviterId)) groupsChanged = this.linkTogether(room, inviterId, accepterId);
    return { kind, groupsChanged };
  }

  // Links two players who agreed to play together. If one of them is on a link with room, the
  // other joins it; if that can't be, both unplug from what they had and link with each other.
  // Returns true when the groups changed.
  linkTogether(room, firstId, secondId) {
    let fromGroup;
    let toGroup;
    try {
      ({ fromGroup, toGroup } = this.checkLink(room, firstId, secondId));
    } catch (error) {
      if (error.code === 'already_linked') return false;
      if (error.code !== 'busy' && error.code !== 'group_full') throw error;
      this.removeFromGroup(room, firstId);
      this.removeFromGroup(room, secondId);
      room.groups.push([
        { id: firstId, slot: 0 },
        { id: secondId, slot: 1 },
      ]);
      return true;
    }
    if (toGroup) {
      toGroup.push({ id: firstId, slot: freeSlot(toGroup) });
    } else if (fromGroup) {
      fromGroup.push({ id: secondId, slot: freeSlot(fromGroup) });
    } else {
      room.groups.push([
        { id: firstId, slot: 0 },
        { id: secondId, slot: 1 },
      ]);
    }
    return true;
  }

  // Returns true when there was such a request.
  declineLink(room, accepterId, requesterId) {
    return room.requests.delete(`${requesterId}>${accepterId}`);
  }

  // Takes a player out of their link group. Returns true when the groups changed.
  removeFromGroup(room, playerId) {
    const group = this.groupOf(room, playerId);
    if (!group) return false;
    const index = group.findIndex((member) => member.id === playerId);
    const leaderLeft = group[index].slot === 0;
    group.splice(index, 1);
    if (group.length < 2) {
      room.groups.splice(room.groups.indexOf(group), 1);
    } else if (leaderLeft) {
      // Someone has to lead the link: give everyone left new places on the cable.
      group.sort((a, b) => a.slot - b.slot).forEach((member, i) => (member.slot = i));
    }
    return true;
  }

  // Who should receive a data packet sent by `fromId` to `targetId`. In link rooms that
  // is only the sender's link group.
  recipients(room, fromId, targetId) {
    let candidates;
    if (room.kind === 'link') {
      const group = this.groupOf(room, fromId);
      if (!group) return [];
      candidates = group.map((member) => member.id).filter((id) => id !== fromId);
    } else {
      candidates = [...room.players.keys()].filter((id) => id !== fromId);
    }
    if (targetId !== BROADCAST) candidates = candidates.filter((id) => id === targetId);
    // Players who lost their connection get nothing until they are back.
    return candidates.map((id) => room.players.get(id)).filter((player) => player && player.connection);
  }

  publicRooms() {
    return [...this.rooms.values()]
      .filter((room) => room.isPublic && room.players.size < room.maxPlayers && [...room.players.values()].some((p) => !p.away))
      .sort((a, b) => b.createdAt - a.createdAt)
      .slice(0, 200)
      .map((room) => describeRoom(room));
  }
}

function freeSlot(group) {
  const used = new Set(group.map((member) => member.slot));
  let slot = 0;
  while (used.has(slot)) slot++;
  return slot;
}

export function describeRoom(room) {
  return {
    code: room.code,
    kind: room.kind,
    system: room.system,
    core: room.core,
    delay: room.delay,
    mode: room.mode,
    modeLabel: MODES[room.mode]?.label ?? '',
    game: room.game,
    host: room.players.get(room.ownerId)?.name ?? '',
    players: room.players.size,
    maxPlayers: room.maxPlayers,
    public: room.isPublic,
  };
}

export function describePlayers(room) {
  return [...room.players.values()].map((p) => (p.away ? { id: p.id, name: p.name, away: true } : { id: p.id, name: p.name }));
}

export function describeGroups(room) {
  return room.groups.map((group) => group.map((member) => ({ id: member.id, slot: member.slot })));
}
