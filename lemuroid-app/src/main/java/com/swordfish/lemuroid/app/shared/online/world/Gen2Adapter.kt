package com.swordfish.lemuroid.app.shared.online.world

import java.util.concurrent.ConcurrentHashMap

/**
 * Gold, Silver and Crystal. Addresses come from the pret decompilation projects (pokegold,
 * pokecrystal); Crystal 1.0 and 1.1 share them.
 *
 * Position: the player's tile (wXCoord/wYCoord) only changes when a step ends, while
 * wPlayerBGMapOffsetX/Y moves by the step speed every frame of the step. The offsets rest on
 * multiples of 16, so their low 4 bits are how far into the step the player is.
 *
 * Pictures: a character is 24 tiles (12 standing, 12 walking) and each of the 16 facings is
 * built from 4 tiles of it, possibly mirrored, as the game's Facings table says.
 */
class Gen2Adapter(
    private val rom: Rom,
    private val layout: Layout,
) : WorldAdapter {
    data class Layout(
        val world: String,
        // Work RAM addresses as the game sees them ($C000-$DFFF, bank 1 for $D000+).
        val mapGroup: Int,
        val mapNumber: Int,
        val xCoord: Int,
        val yCoord: Int,
        val playerStruct: Int,
        val mapStatus: Int,
        val battleMode: Int,
        val stepFlags: Int,
        val stepDirection: Int,
        val bgMapOffsetX: Int,
        val bgMapOffsetY: Int,
        // Open menus (the start menu is 1), and the player event a script runs for.
        val windowStackSize: Int,
        val scriptRunning: Int,
        // The current map's size in blocks (2x2 steps), and its 4 connections (north, south,
        // west, east; 12 bytes each, one after the other) with the flags saying which are set.
        val mapHeight: Int,
        val mapWidth: Int,
        val connectionFlags: Int,
        val northConnection: Int,
        // ROM offsets.
        val facings: Int,
        val overworldSprites: Int,
        val objectPalettes: Int,
        val mapGroupPointers: Int,
        val landmarks: Int,
        // Running the game's own link trade or battle where the player stands; null: not (yet)
        // checked for this game.
        val inPlace: InPlace? = null,
        // Joining the host's world and coming back home; null: not checked for this game.
        val visit: Visit? = null,
    )

    /**
     * What a visit to the host's world needs (see [WorldVisit]): the party (6 slots of a
     * 48-byte record, then 6 trainer names and 6 nicknames of 11 bytes), the Pokédex flags, and
     * the game's own "save everything" routine (it asks nothing). Scripts that load a map go
     * in a text buffer, since loading a map clears the in-place link script buffer.
     */
    data class Visit(
        val partyCount: Int,
        val partyMons: Int,
        val partyTrainers: Int,
        val partyNicknames: Int,
        val pokedexCaught: Int,
        val pokedexSeen: Int,
        val warpScriptBuffer: Int,
        val saveBank: Int,
        val saveAddress: Int,
        // wPlayerName
        val playerName: Int,
    )

    /**
     * What the game's link receptionist does (set up the request, wait for the friend, save, check
     * the link), as a 24-byte script in an unused part of work RAM, queued through the game's own
     * "run this script" slot. It ends by jumping to the console script of the Trade Center or the
     * Colosseum in ROM, which starts the trade or battle and then reloads the current map.
     */
    data class InPlace(
        val scriptBuffer: Int,
        val queueFlag: Int,
        val linkMode: Int,
        val consoleBank: Int,
        val tradeConsole: Int,
        val battleConsole: Int,
        // Special numbers in the game's table.
        val setTradeRequest: Int,
        val setBattleRequest: Int,
        val waitForFriend: Int,
        val quickSave: Int,
        val checkLinkTimeout: Int,
        val tradeCenter: Int,
        val colosseum: Int,
        val leaveLink: Int,
        // Script command numbers that differ between Gold/Silver and Crystal.
        val newLoadMap: Int,
        val end: Int,
        // For the link report: wScriptVar and hSerialConnectionStatus.
        val scriptVar: Int = 0,
        val connectionStatus: Int = 0,
    )

    override val world: String get() = layout.world
    override val screenWidth: Int = 160
    override val screenHeight: Int = 144

    private val pictures = ConcurrentHashMap<Long, SpriteImage>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    // Bank 0 ($C000) and bank 1 ($D000) sit one after the other in the work RAM block.
    private fun ram(address: Int): Long = GameMemory.SYSTEM_RAM_BASE + (address - 0xC000)

    override fun trainerName(memory: GameMemory): String? {
        val visit = layout.visit ?: return null
        if (memory.u8(ram(layout.mapStatus)) != MAPSTATUS_HANDLE) return null
        return TrainerNames.gen2Text(memory.read(ram(visit.playerName), TrainerNames.GEN2_NAME_LENGTH) ?: return null)
    }

    override fun read(memory: GameMemory): Avatar? {
        if (memory.u8(ram(layout.mapStatus)) != MAPSTATUS_HANDLE) return null
        if (memory.u8(ram(layout.battleMode)) != 0) return null

        val player = layout.playerStruct
        val sprite = memory.u8(ram(player + OBJECT_SPRITE)) ?: return null
        val flags = memory.u8(ram(player + OBJECT_FLAGS1)) ?: return null
        val facing = memory.u8(ram(player + OBJECT_FACING)) ?: return null
        if (sprite == 0 || flags and FLAG_INVISIBLE != 0 || facing >= STEP_FACINGS) return null
        val palette = (memory.u8(ram(player + OBJECT_PALETTE)) ?: 0) and 7

        val group = memory.u8(ram(layout.mapGroup)) ?: return null
        val number = memory.u8(ram(layout.mapNumber)) ?: return null
        val tileX = memory.u8(ram(layout.xCoord)) ?: return null
        val tileY = memory.u8(ram(layout.yCoord)) ?: return null
        val offsetX = memory.u8(ram(layout.bgMapOffsetX)) ?: return null
        val offsetY = memory.u8(ram(layout.bgMapOffsetY)) ?: return null

        var x = tileX * 16
        var y = tileY * 16
        if (memory.u8(ram(layout.stepFlags)) != 0) {
            when (memory.u8(ram(layout.stepDirection))) {
                DOWN -> y += (-offsetY) and 15
                UP -> y -= offsetY and 15
                LEFT -> x -= offsetX and 15
                RIGHT -> x += (-offsetX) and 15
            }
        }

        // Where the game draws our sprite (the same sums the game uses for its OAM).
        val screenX =
            ((memory.u8(ram(player + OBJECT_SPRITE_X)) ?: 0) + (memory.u8(ram(player + OBJECT_SPRITE_X_OFFSET)) ?: 0) + offsetX) and 0xFF
        val screenY =
            ((memory.u8(ram(player + OBJECT_SPRITE_Y)) ?: 0) + (memory.u8(ram(player + OBJECT_SPRITE_Y_OFFSET)) ?: 0) - 4 + offsetY) and 0xFF

        // The start menu opens a window; text boxes (people, signs, trainers, items) run a script.
        val event = memory.u8(ram(layout.scriptRunning)) ?: 0
        val covered = (memory.u8(ram(layout.windowStackSize)) ?: 0) != 0 || event !in QUIET_EVENTS

        return Avatar(
            map = "$group.$number",
            x = x,
            y = y,
            look = Look(sprite or (palette shl 8), facing, false),
            screenX = signed(screenX),
            screenY = signed(screenY),
            covered = covered,
            facing = FACINGS[facing / 4],
        )
    }

    /**
     * The game keeps the current map's connections in RAM. Each says which map is joined on that
     * side and how coordinates shift on the way (yalign/xalign: where the player lands there).
     */
    override fun neighbors(memory: GameMemory): Map<String, MapOffset> {
        val flags = memory.u8(ram(layout.connectionFlags)) ?: return emptyMap()
        val width = (memory.u8(ram(layout.mapWidth)) ?: return emptyMap()) * 2
        val height = (memory.u8(ram(layout.mapHeight)) ?: return emptyMap()) * 2
        val result = HashMap<String, MapOffset>()
        // North, south, west, east; flag bits 3, 2, 1, 0.
        for (side in 0 until 4) {
            if (flags and (8 shr side) == 0) continue
            val base = layout.northConnection + side * CONNECTION_SIZE
            val group = memory.u8(ram(base)) ?: continue
            val number = memory.u8(ram(base + 1)) ?: continue
            if (group == 0 || group == 0xFF) continue
            val yAlign = memory.s8(ram(base + CONNECTION_Y_ALIGN)) ?: continue
            val xAlign = memory.s8(ram(base + CONNECTION_X_ALIGN)) ?: continue
            // Steps on our map for step (0, 0) on theirs.
            val (dx, dy) =
                when (side) {
                    0 -> -xAlign to -yAlign - 1
                    1 -> -xAlign to -yAlign + height
                    2 -> -xAlign - 1 to -yAlign
                    else -> -xAlign + width to -yAlign
                }
            result["$group.$number"] = MapOffset(dx * 16, dy * 16)
        }
        return result
    }

    override fun inPlaceLink(kind: String): MemoryPatch? {
        val link = layout.inPlace ?: return null
        val battle = kind == Talk.BATTLE
        val console = if (battle) link.battleConsole else link.tradeConsole
        // The console script must be in this ROM exactly as expected: special, reload map, end.
        val expected =
            byteArrayOf(
                SPECIAL, (if (battle) link.colosseum else link.tradeCenter).toByte(), 0,
                link.newLoadMap.toByte(), MAPSETUP_LINKRETURN, link.end.toByte(),
            )
        val found = ByteArray(expected.size) { rom.u8(romOffset(link.consoleBank, console) + it.toLong())?.toByte() ?: return null }
        if (!found.contentEquals(expected)) return null
        // Failed steps end at the console script's "end", like a receptionist saying no.
        val fail = console + 5
        // The game saves before linking (as its receptionist does), but without asking: the game's
        // own "save everything" routine, as for coming home from a visit.
        val save = saveRoutine() ?: return null
        val script =
            byteArrayOf(
                SPECIAL, (if (battle) link.setBattleRequest else link.setTradeRequest).toByte(), 0,
                SPECIAL, link.waitForFriend.toByte(), 0, IFFALSE, fail.toByte(), (fail shr 8).toByte(),
                CALLASM, save.first.toByte(), save.second.toByte(), (save.second shr 8).toByte(),
                SPECIAL, link.checkLinkTimeout.toByte(), 0, IFFALSE, fail.toByte(), (fail shr 8).toByte(),
                SJUMP, console.toByte(), (console shr 8).toByte(),
            )
        return queueScript(link, script)
    }

    override fun inPlaceLinkCleanup(): MemoryPatch? {
        val link = layout.inPlace ?: return null
        return queueScript(link, byteArrayOf(SPECIAL, link.leaveLink.toByte(), 0, link.end.toByte()))
    }

    // For the link report: the game's state, as numbers.
    override fun linkDebug(memory: GameMemory): String? {
        val link = layout.inPlace ?: return null
        fun hex(address: Int) = memory.u8(ram(address))?.let { "%02X".format(it) } ?: "--"
        return "script=${hex(layout.scriptRunning)} queue=${hex(link.queueFlag)} link=${hex(link.linkMode)} " +
            "map=${hex(layout.mapStatus)} windows=${hex(layout.windowStackSize)} var=${hex(link.scriptVar)} " +
            "cable=${memory.u8(link.connectionStatus.toLong())?.let { "%02X".format(it) } ?: "--"}"
    }

    // Our script is running while the game runs a map script; the trade and battle screens run inside it.
    override fun inPlaceLinkRunning(memory: GameMemory): Boolean = memory.u8(ram(layout.scriptRunning)) == MAP_SCRIPT

    // The script goes in the buffer; then the slot gets its bank and address, and the flag last.
    private fun queueScript(
        link: InPlace,
        script: ByteArray,
        buffer: Int = link.scriptBuffer,
    ): MemoryPatch {
        require(script.size <= SCRIPT_BUFFER_SIZE)
        return MemoryPatch(
            listOf(
                ram(buffer) to script,
                ram(link.queueFlag + 1) to byteArrayOf(link.consoleBank.toByte(), buffer.toByte(), (buffer shr 8).toByte()),
                ram(link.queueFlag) to byteArrayOf(1),
            ),
        )
    }

    // The game's own warp, as a script ends with when it moves the player.
    override fun visitWarp(
        map: String,
        x: Int,
        y: Int,
    ): MemoryPatch? {
        val link = layout.inPlace ?: return null
        val visit = layout.visit ?: return null
        val (group, number) = map.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: return null
        if (group !in 1..0xFF || number !in 1..0xFF || x !in 0..0xFF || y !in 0..0xFF) return null
        val script = byteArrayOf(WARP, group.toByte(), number.toByte(), x.toByte(), y.toByte(), link.end.toByte())
        return queueScript(link, script, visit.warpScriptBuffer)
    }

    override fun party(memory: GameMemory): List<PartyMon>? {
        val visit = layout.visit ?: return null
        val count = memory.u8(ram(visit.partyCount)) ?: return null
        if (count > PARTY_LENGTH) return null
        return (0 until count).map { slot ->
            val species = memory.u8(ram(visit.partyCount + 1 + slot)) ?: return null
            val mon = memory.read(ram(visit.partyMons + slot * MON_SIZE), MON_SIZE) ?: return null
            val trainer = memory.read(ram(visit.partyTrainers + slot * NAME_SIZE), NAME_SIZE) ?: return null
            val nickname = memory.read(ram(visit.partyNicknames + slot * NAME_SIZE), NAME_SIZE) ?: return null
            // Who it is: trainer ID, DVs and trainer name (the species changes when it evolves).
            val key = (mon.copyOfRange(MON_TRAINER_ID, MON_TRAINER_ID + 2) + mon.copyOfRange(MON_DVS, MON_DVS + 2) + trainer).toHex()
            PartyMon(key, species, mon + trainer + nickname)
        }
    }

    override fun writeParty(party: List<PartyMon>): MemoryPatch? {
        val visit = layout.visit ?: return null
        if (party.size > PARTY_LENGTH || party.any { it.data.size != MON_SIZE + 2 * NAME_SIZE }) return null
        val writes = ArrayList<Pair<Long, ByteArray>>()
        writes += ram(visit.partyCount) to byteArrayOf(party.size.toByte()) + party.map { it.species.toByte() } + byteArrayOf(-1)
        party.forEachIndexed { slot, mon ->
            writes += ram(visit.partyMons + slot * MON_SIZE) to mon.data.copyOfRange(0, MON_SIZE)
            writes += ram(visit.partyTrainers + slot * NAME_SIZE) to mon.data.copyOfRange(MON_SIZE, MON_SIZE + NAME_SIZE)
            writes += ram(visit.partyNicknames + slot * NAME_SIZE) to mon.data.copyOfRange(MON_SIZE + NAME_SIZE, MON_SIZE + 2 * NAME_SIZE)
        }
        return MemoryPatch(writes)
    }

    // Pokémon that came in a trade are in the Pokédex as caught (and seen), as the game does.
    override fun registerInPokedex(
        memory: GameMemory,
        species: List<Int>,
    ): MemoryPatch? {
        val visit = layout.visit ?: return null
        val writes = ArrayList<Pair<Long, ByteArray>>()
        for (table in listOf(visit.pokedexCaught, visit.pokedexSeen)) {
            val flags = memory.read(ram(table), POKEDEX_BYTES) ?: return null
            for (number in species) {
                if (number !in 1..NUM_POKEMON) continue
                val bit = number - 1
                flags[bit / 8] = (flags[bit / 8].toInt() or (1 shl (bit % 8))).toByte()
            }
            writes += ram(table) to flags
        }
        return MemoryPatch(writes)
    }

    override val canSave: Boolean get() = layout.inPlace != null && saveRoutine() != null

    override fun saveGame(memory: GameMemory): MemoryPatch? = saveGame()

    fun saveGame(): MemoryPatch? {
        val link = layout.inPlace ?: return null
        val (bank, address) = saveRoutine() ?: return null
        val script = byteArrayOf(CALLASM, bank.toByte(), address.toByte(), (address shr 8).toByte(), link.end.toByte())
        return queueScript(link, script)
    }

    // The game's "save everything" routine (bank, address), when it is in this ROM as expected:
    // a call to the real save, then return.
    private fun saveRoutine(): Pair<Int, Int>? {
        val visit = layout.visit ?: return null
        val start = romOffset(visit.saveBank, visit.saveAddress).toLong()
        if (rom.u8(start) != CALL || rom.u8(start + 3) != RET) return null
        return visit.saveBank to visit.saveAddress
    }

    override fun linkActive(memory: GameMemory): Boolean {
        val link = layout.inPlace ?: return false
        return (memory.u8(ram(link.linkMode)) ?: 0) != 0
    }

    override fun scriptPending(memory: GameMemory): Boolean {
        val link = layout.inPlace ?: return false
        return (memory.u8(ram(link.queueFlag)) ?: 0) != 0 || memory.u8(ram(layout.scriptRunning)) == MAP_SCRIPT
    }

    private val places = ConcurrentHashMap<String, Place>()

    // The map header in ROM says which town map landmark the map belongs to.
    override fun place(map: String): Place? {
        places[map]?.let { return it }
        val place =
            runCatching {
                val (group, number) = map.split('.').map { it.toInt() }
                val mapsBank = layout.mapGroupPointers / 0x4000
                val groupPointer = rom.u16(layout.mapGroupPointers + (group - 1) * 2L) ?: return null
                val header = romOffset(mapsBank, groupPointer) + (number - 1) * MAP_HEADER_SIZE
                val environment = rom.u8(header + 2L) ?: return null
                val landmark = rom.u8(header + 5L) ?: return null
                val landmarksBank = layout.landmarks / 0x4000
                val namePointer = rom.u16(layout.landmarks + landmark * 4L + 2) ?: return null
                val name = decodeText(romOffset(landmarksBank, namePointer)) ?: return null
                Place(titleCase(name), environment == INDOOR || environment == GATE)
            }.getOrNull() ?: return null
        places[map] = place
        return place
    }

    // The game's own text encoding (letters, digits and a few signs are enough for place names).
    private fun decodeText(offset: Int): String? {
        val text = StringBuilder()
        for (i in 0 until 32) {
            val c = rom.u8(offset + i.toLong()) ?: return null
            when (c) {
                0x50 -> break
                in 0x80..0x99 -> text.append('A' + (c - 0x80))
                in 0xA0..0xB9 -> text.append('a' + (c - 0xA0))
                in 0xF6..0xFF -> text.append('0' + (c - 0xF6))
                0x54 -> text.append("POKé")
                0xE3 -> text.append('-')
                0xE8 -> text.append('.')
                0xE0 -> text.append('\'')
                0xEA -> text.append('é')
                else -> text.append(' ')
            }
        }
        return text.toString().trim().ifEmpty { null }
    }

    override fun sprite(look: Look): SpriteImage? {
        val key = (look.sprite.toLong() shl 16) or look.frame.toLong()
        pictures[key]?.let { return it }
        if (key in missing) return null
        val picture = runCatching { draw(look.sprite and 0xFF, look.sprite shr 8, look.frame) }.getOrNull()
        if (picture == null) missing += key else pictures[key] = picture
        return picture
    }

    private fun draw(
        sprite: Int,
        paletteIndex: Int,
        facing: Int,
    ): SpriteImage? {
        if (sprite == 0 || facing !in 0 until STEP_FACINGS) return null
        val entry = layout.overworldSprites + (sprite - 1) * SPRITE_ENTRY_SIZE
        val pointer = rom.u16(entry.toLong()) ?: return null
        val bank = rom.u8(entry + 3L) ?: return null
        val graphics = romOffset(bank, pointer)
        val palette = palette(paletteIndex) ?: return null

        val pieces = romOffset(1, rom.u16(layout.facings + facing * 2L) ?: return null)
        val count = rom.u8(pieces.toLong()) ?: return null
        if (count !in 1..8) return null

        val pixels = IntArray(16 * 16)
        for (i in 0 until count) {
            val base = pieces + 1L + i * 4
            val dy = rom.u8(base) ?: return null
            val dx = rom.u8(base + 1) ?: return null
            val attributes = rom.u8(base + 2) ?: return null
            val tile = rom.u8(base + 3) ?: return null
            // Walking frames ($80+) are the second 12 tiles of the character.
            val index = if (tile < 0x80) tile else 12 + (tile - 0x80)
            val flipX = attributes and OAM_XFLIP != 0
            val flipY = attributes and OAM_YFLIP != 0
            for (row in 0 until 8) {
                val low = rom.u8(graphics + index * 16L + row * 2) ?: return null
                val high = rom.u8(graphics + index * 16L + row * 2 + 1) ?: return null
                for (column in 0 until 8) {
                    val bit = 7 - column
                    val color = ((low shr bit) and 1) or (((high shr bit) and 1) shl 1)
                    if (color == 0) continue
                    val px = dx + if (flipX) 7 - column else column
                    val py = dy + if (flipY) 7 - row else row
                    if (px in 0 until 16 && py in 0 until 16) pixels[py * 16 + px] = palette[color]
                }
            }
        }
        return SpriteImage(16, 16, pixels)
    }

    // Colors from the game's daytime character palettes.
    private fun palette(index: Int): IntArray? {
        val base = layout.objectPalettes + DAY_PALETTES + index * 8L
        return IntArray(4) { color -> bgr555ToArgb(rom.u16(base + color * 2) ?: return null) }
    }

    private fun signed(value: Int): Int = if (value >= 0xC0) value - 0x100 else value

    companion object {
        private const val MAPSTATUS_HANDLE = 2
        private const val STEP_FACINGS = 16

        private const val OBJECT_SPRITE = 0x00
        private const val OBJECT_FLAGS1 = 0x04
        private const val OBJECT_PALETTE = 0x06
        private const val OBJECT_FACING = 0x0D
        private const val OBJECT_SPRITE_X = 0x17
        private const val OBJECT_SPRITE_Y = 0x18
        private const val OBJECT_SPRITE_X_OFFSET = 0x19
        private const val OBJECT_SPRITE_Y_OFFSET = 0x1A
        private const val FLAG_INVISIBLE = 0x01

        private const val DOWN = 0
        private const val UP = 1
        private const val LEFT = 2
        private const val RIGHT = 3

        // Player events that show nothing on screen: none, crossing into the next map, a
        // warp, falling through a hole, and turning on the spot (a short script while walking).
        private val QUIET_EVENTS = setOf(0, 4, 5, 6, 9)

        private const val OAM_XFLIP = 0x20
        private const val OAM_YFLIP = 0x40

        private const val SPRITE_ENTRY_SIZE = 6

        private val FACINGS = arrayOf(Facing.DOWN, Facing.UP, Facing.LEFT, Facing.RIGHT)

        // Script commands (macros/scripts/events.asm) and the map setup after a link.
        private const val SJUMP: Byte = 0x03
        private const val IFFALSE: Byte = 0x08
        private const val CALLASM: Byte = 0x0E
        private const val SPECIAL: Byte = 0x0F
        private const val WARP: Byte = 0x3C
        // CPU instructions, to check the save routine.
        private const val CALL = 0xCD
        private const val RET = 0xC9
        private const val MAPSETUP_LINKRETURN: Byte = 0xF8.toByte()
        private const val MAP_SCRIPT = 0xFF
        private const val SCRIPT_BUFFER_SIZE = 24

        private const val PARTY_LENGTH = 6
        private const val MON_SIZE = 48
        private const val MON_TRAINER_ID = 6
        private const val MON_DVS = 21
        private const val NAME_SIZE = 11
        private const val NUM_POKEMON = 251
        private const val POKEDEX_BYTES = 32

        private const val CONNECTION_SIZE = 12
        private const val CONNECTION_Y_ALIGN = 8
        private const val CONNECTION_X_ALIGN = 9
        private const val MAP_HEADER_SIZE = 9

        // Map environments (constants/map_data_constants.asm).
        private const val INDOOR = 3
        private const val GATE = 6

        // Morning, day, night and dark sets of 8 palettes of 4 colors each.
        private const val DAY_PALETTES = 64

        /** ROM file offset of a banked address. */
        fun romOffset(
            bank: Int,
            address: Int,
        ): Int = if (address < 0x4000) address else bank * 0x4000 + (address - 0x4000)

        val CRYSTAL =
            Layout(
                world = "johto-c",
                mapGroup = 0xDCB5,
                mapNumber = 0xDCB6,
                yCoord = 0xDCB7,
                xCoord = 0xDCB8,
                playerStruct = 0xD4D6,
                mapStatus = 0xD432,
                battleMode = 0xD22D,
                stepFlags = 0xD150,
                stepDirection = 0xD151,
                bgMapOffsetX = 0xD14C,
                bgMapOffsetY = 0xD14D,
                windowStackSize = 0xCF78,
                scriptRunning = 0xD438,
                mapHeight = 0xD19E,
                mapWidth = 0xD19F,
                connectionFlags = 0xD1A8,
                northConnection = 0xD1A9,
                facings = romOffset(0x01, 0x4049),
                overworldSprites = romOffset(0x05, 0x4736),
                objectPalettes = romOffset(0x02, 0x7469),
                mapGroupPointers = romOffset(0x25, 0x4000),
                landmarks = romOffset(0x72, 0x68C3),
                inPlace =
                    InPlace(
                        // wUnusedMapBuffer (24 bytes nothing uses) and wMapReentryScriptQueueFlag.
                        scriptBuffer = 0xC7E8,
                        queueFlag = 0xD45C,
                        linkMode = 0xC2DC,
                        consoleBank = 0x64,
                        tradeConsole = 0x7405,
                        battleConsole = 0x7493,
                        setTradeRequest = 0x01,
                        setBattleRequest = 0x09,
                        waitForFriend = 0x02,
                        quickSave = 0x04,
                        checkLinkTimeout = 0x03,
                        tradeCenter = 0x0D,
                        colosseum = 0x0E,
                        leaveLink = 0x08,
                        newLoadMap = 0x8A,
                        end = 0x91,
                        scriptVar = 0xC2DD,
                        connectionStatus = 0xFFCB,
                    ),
                visit =
                    Visit(
                        partyCount = 0xDCD7,
                        partyMons = 0xDCDF,
                        partyTrainers = 0xDDFF,
                        partyNicknames = 0xDE41,
                        pokedexCaught = 0xDE99,
                        pokedexSeen = 0xDEB9,
                        // wStringBuffer2
                        warpScriptBuffer = 0xD086,
                        saveBank = 0x05,
                        saveAddress = 0x4B85,
                        playerName = 0xD47D,
                    ),
            )

        val GOLD_SILVER =
            Layout(
                world = "johto-gs",
                mapGroup = 0xDA00,
                mapNumber = 0xDA01,
                yCoord = 0xDA02,
                xCoord = 0xDA03,
                playerStruct = 0xD1FD,
                mapStatus = 0xD159,
                battleMode = 0xD116,
                stepFlags = 0xCE85,
                stepDirection = 0xCE86,
                bgMapOffsetX = 0xCE81,
                bgMapOffsetY = 0xCE82,
                windowStackSize = 0xCEAF,
                scriptRunning = 0xD15F,
                mapHeight = 0xD087,
                mapWidth = 0xD088,
                connectionFlags = 0xD091,
                northConnection = 0xD092,
                facings = romOffset(0x01, 0x404A),
                overworldSprites = romOffset(0x05, 0x47DE),
                objectPalettes = romOffset(0x02, 0x78AE),
                mapGroupPointers = romOffset(0x25, 0x40ED),
                landmarks = romOffset(0x24, 0x6382),
                inPlace =
                    InPlace(
                        // The same 24 unused bytes and script slot as in Crystal, elsewhere.
                        scriptBuffer = 0xC6E8,
                        queueFlag = 0xD183,
                        linkMode = 0xD042,
                        consoleBank = 0x5C,
                        tradeConsole = 0x54AF,
                        battleConsole = 0x5530,
                        setTradeRequest = 0x01,
                        setBattleRequest = 0x09,
                        waitForFriend = 0x02,
                        quickSave = 0x04,
                        checkLinkTimeout = 0x03,
                        tradeCenter = 0x0D,
                        colosseum = 0x0E,
                        leaveLink = 0x08,
                        newLoadMap = 0x89,
                        end = 0x90,
                        scriptVar = 0xD173,
                        connectionStatus = 0xFFCD,
                    ),
                visit =
                    Visit(
                        partyCount = 0xDA22,
                        partyMons = 0xDA2A,
                        partyTrainers = 0xDB4A,
                        partyNicknames = 0xDB8C,
                        pokedexCaught = 0xDBE4,
                        pokedexSeen = 0xDC04,
                        // wStringBuffer2
                        warpScriptBuffer = 0xCF7E,
                        saveBank = 0x05,
                        saveAddress = 0x4C23,
                        playerName = 0xD1A3,
                    ),
            )

        /**
         * The layout for a Game Boy Color ROM, from its header (title at $134, game code at
         * $13F), or null. Only the English versions: other languages move things around.
         */
        fun layoutFor(
            title: String,
            gameCode: String,
        ): Layout? =
            when (title.trimEnd('\u0000', ' ') to gameCode) {
                "PM_CRYSTAL" to "BYTE" -> CRYSTAL
                "POKEMON_GLD" to "AAUE", "POKEMON_SLV" to "AAXE" -> GOLD_SILVER
                else -> null
            }
    }
}
