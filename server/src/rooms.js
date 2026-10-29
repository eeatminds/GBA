// Room bookkeeping for the RetroLink relay. Pure logic, no networking, so it is easy to test.

export const BROADCAST = 0xffff;

// Player limits come from the gpSP core: link cable modes allow 4 GBAs, and the
// wireless adapter allows up to 5 players in the games that use it.
export const MODES = {
  auto: { label: 'Automatic', maxPlayers: 4 },
  poke: { label: 'Link cable (Pokemon Gen 3)', maxPlayers: 4 },
  rfu: { label: 'Wireless adapter', maxPlayers: 5 },
  aw1: { label: 'Link cable (Advance Wars)', maxPlayers: 4 },
  aw2: { label: 'Link cable (Advance Wars 2)', maxPlayers: 4 },
};

export const SYSTEMS = new Set(['gba']);

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
  constructor({ maxRooms = 5000, random = Math.random } = {}) {
    this.maxRooms = maxRooms;
    this.random = random;
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
    const system = typeof request.system === 'string' ? request.system : 'gba';
    if (!SYSTEMS.has(system)) throw new RoomError('bad_request', 'This system does not support online rooms yet.');

    const modeKey = typeof request.mode === 'string' && MODES[request.mode] ? request.mode : 'auto';
    const mode = MODES[modeKey];
    const requestedMax = Number.isInteger(request.maxPlayers) ? request.maxPlayers : mode.maxPlayers;
    const maxPlayers = Math.max(2, Math.min(mode.maxPlayers, requestedMax));

    const code = this.newCode();
    const host = { id: 0, name: cleanName(request.name, 'Player 1'), connection };
    const room = {
      code,
      system,
      mode: modeKey,
      game: cleanText(request.game, MAX_GAME_LENGTH) || 'Game Boy Advance',
      isPublic: request.public === true,
      protocol: typeof request.protocol === 'string' ? request.protocol.slice(0, 64) : '',
      maxPlayers,
      createdAt: Date.now(),
      players: new Map([[0, host]]),
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

    let id = -1;
    for (let candidate = 1; candidate < room.maxPlayers; candidate++) {
      if (!room.players.has(candidate)) {
        id = candidate;
        break;
      }
    }
    if (id < 0) throw new RoomError('room_full', 'This room is full.');

    const player = { id, name: cleanName(request.name, `Player ${id + 1}`), connection };
    room.players.set(id, player);
    return { room, player };
  }

  // Returns { closed: true } when the host left and the room is gone.
  leave(room, playerId) {
    if (!this.rooms.has(room.code) || !room.players.has(playerId)) return { closed: false, removed: false };
    room.players.delete(playerId);
    if (playerId === 0) {
      this.rooms.delete(room.code);
      return { closed: true, removed: true };
    }
    return { closed: false, removed: true };
  }

  // Who should receive a data packet sent by `fromId` to `targetId`.
  recipients(room, fromId, targetId) {
    if (targetId === BROADCAST) {
      return [...room.players.values()].filter((p) => p.id !== fromId);
    }
    if (targetId === fromId) return [];
    const target = room.players.get(targetId);
    return target ? [target] : [];
  }

  publicRooms() {
    return [...this.rooms.values()]
      .filter((room) => room.isPublic && room.players.size < room.maxPlayers)
      .sort((a, b) => b.createdAt - a.createdAt)
      .slice(0, 200)
      .map((room) => describeRoom(room));
  }
}

export function describeRoom(room) {
  return {
    code: room.code,
    system: room.system,
    mode: room.mode,
    modeLabel: MODES[room.mode].label,
    game: room.game,
    host: room.players.get(0)?.name ?? '',
    players: room.players.size,
    maxPlayers: room.maxPlayers,
    public: room.isPublic,
  };
}

export function describePlayers(room) {
  return [...room.players.values()].map((p) => ({ id: p.id, name: p.name }));
}
