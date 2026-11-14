package com.swordfish.lemuroid.app.shared.online.world

import java.util.concurrent.ConcurrentHashMap

/**
 * Red, Blue and Yellow (English). Addresses come from the pret decompilation projects (pokered,
 * pokeyellow); Red and Blue share them, Yellow's work RAM sits one byte earlier from wCurMap on.
 *
 * Position: the player's step (wXCoord/wYCoord) only changes when a step ends. A step is eight
 * moves of two pixels; wWalkCounter counts them down, and the player's step vector says which way.
 *
 * Pictures: a character is 12 standing tiles and 12 walking tiles; each of its 16 frames (4
 * facings, 4 walking phases) is 4 of them, mirrored for some, as the game's own table says.
 */
class Gen1Adapter(
    private val rom: Rom,
    private val layout: Layout,
) : WorldAdapter {
    data class Layout(
        val world: String,
        // Work RAM addresses as the game sees them ($C000-$DFFF).
        val curMap: Int,
        val xCoord: Int,
        val yCoord: Int,
        val walkCounter: Int,
        val fontLoaded: Int,
        val isInBattle: Int,
        val mapHeight: Int,
        val mapWidth: Int,
        // Which of the 4 connections the current map has, and the first of them (north, south,
        // west, east; 11 bytes each, one after the other).
        val connectionFlags: Int,
        val northConnection: Int,
        val playerName: Int,
        // Yellow on a Game Boy Color: where the current map's colors are (a pointer into ROM bank
        // [colorsBank]); 0 for Red and Blue, which are drawn in the Game Boy Pocket's greys.
        val cgbBasePalPointers: Int,
        val colorsBank: Int,
        // ROM: the characters' graphics, and the town map's names (outdoor maps by number,
        // indoor maps by group).
        val spriteSheets: Int,
        val outdoorNames: Int,
        val indoorNames: Int,
        val namesBank: Int,
        // Trading or battling where the players stand (see [InPlace]).
        val inPlace: InPlace,
    )

    /**
     * Red, Blue and Yellow have no "run this script" slot. Instead the app points the current
     * map's script (which the overworld calls every frame) at a short routine of its own in work
     * RAM, which does what the Cable Club does without its rooms: part A (in a scratch area only
     * slot machines, battle animations and scripted walks use) meets the friend (the link core's
     * friend meeting, at its first byte), saves without asking and syncs; part B (in the unused end
     * of the game progress flags, which outlives the battle and the trade) runs the game's own
     * CableClub_DoBattleOrTrade, which reloads the current map when it's done, then closes the link,
     * puts the map's script back and its music.
     */
    data class InPlace(
        val mapScript: Int,
        val linkState: Int,
        val joyIgnore: Int,
        val partB: Int,
        // Routines in the home bank.
        val sendZeroByte: Int,
        val delayFrame: Int,
        val delayFrames: Int,
        val bankswitch: Int,
        val syncAndExchangeNybble: Int,
        val playDefaultMusic: Int,
        // SaveGameData (bank $1C), CableClub_DoBattleOrTrade and CloseLinkConnection (bank 1).
        val saveGameData: Int,
        val battleOrTrade: Int,
        val closeLink: Int,
    )

    override val world: String get() = layout.world
    override val screenWidth: Int = 160
    override val screenHeight: Int = 144

    private val pictures = ConcurrentHashMap<Long, SpriteImage>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    // The colors our game draws characters in now (Yellow on a Game Boy Color: they follow the map).
    @Volatile
    private var colorsPointer = 0

    private fun ram(address: Int): Long = GameMemory.SYSTEM_RAM_BASE + (address - 0xC000)

    override fun trainerName(memory: GameMemory): String? {
        if (memory.u8(ram(layout.isInBattle)) != 0) return null
        return TrainerNames.gen2Text(memory.read(ram(layout.playerName), TrainerNames.GEN2_NAME_LENGTH) ?: return null)
    }

    override fun read(memory: GameMemory): Avatar? {
        if (memory.u8(ram(layout.isInBattle)) != 0) return null
        val picture = memory.u8(ram(PLAYER + SPRITE_PICTURE)) ?: return null
        val image = memory.u8(ram(PLAYER + SPRITE_IMAGE)) ?: return null
        if (picture == 0 || image == 0xFF) return null

        val map = memory.u8(ram(layout.curMap)) ?: return null
        val tileX = memory.u8(ram(layout.xCoord)) ?: return null
        val tileY = memory.u8(ram(layout.yCoord)) ?: return null
        val walk = memory.u8(ram(layout.walkCounter)) ?: 0
        val stepY = memory.s8(ram(PLAYER + SPRITE_Y_STEP)) ?: 0
        val stepX = memory.s8(ram(PLAYER + SPRITE_X_STEP)) ?: 0
        val progress = if (walk in 1..7) (8 - walk) * 2 else 0

        if (layout.cgbBasePalPointers != 0) colorsPointer = memory.u16(ram(layout.cgbBasePalPointers)) ?: 0

        val frame = image and 0x0F
        return Avatar(
            map = "$MAP_GROUP.$map",
            x = tileX * 16 + stepX.coerceIn(-1, 1) * progress,
            y = tileY * 16 + stepY.coerceIn(-1, 1) * progress,
            look = Look(picture, frame, false),
            screenX = memory.u8(ram(PLAYER + SPRITE_SCREEN_X)) ?: 0,
            screenY = memory.u8(ram(PLAYER + SPRITE_SCREEN_Y)) ?: 0,
            // A text box or a menu (they load the font).
            covered = (memory.u8(ram(layout.fontLoaded)) ?: 0) and 1 != 0,
            facing = FACINGS[frame / 4],
        )
    }

    /**
     * The game keeps the current map's connections in RAM: which map is joined on each side, and
     * where the player lands there (the alignments), as the game uses them when walking across.
     */
    // Supported where the layout says how; the work is done in steps (see [inPlaceSteps]).
    override fun inPlaceLink(kind: String): MemoryPatch = MemoryPatch(emptyList())

    override fun inPlaceSteps(
        kind: String,
        now: Long,
    ): InPlaceSteps = InPlaceRun(kind)

    // In-place routine bytes for [kind], putting [mapScript] back as the map's script when done.
    fun inPlaceRoutine(
        kind: String,
        mapScript: Int,
    ): Pair<ByteArray, ByteArray> {
        val link = layout.inPlace
        val done = link.partB + PART_B_DONE
        fun call(address: Int) = listOf(0xCD, address and 0xFF, address shr 8)
        fun word(address: Int) = listOf(address and 0xFF, address shr 8)
        val partA =
            listOf(0x00) + call(link.sendZeroByte) + call(link.delayFrame) + call(link.sendZeroByte) +
                listOf(0x0E, 50) + call(link.delayFrames) +
                listOf(0x06, SAVE_BANK, 0x21) + word(link.saveGameData) + call(link.bankswitch) +
                listOf(0x21) + word(LINK_TIMEOUT_FRAMES) + listOf(0x3E, 0x03, 0x22, 0xAF, 0x77, 0xE0, 0xA9, 0xEA) + word(EXCHANGE_NYBBLE_SEND) +
                call(link.syncAndExchangeNybble) +
                listOf(0x21) + word(LINK_TIMEOUT_FRAMES) + listOf(0x2A, 0xA6, 0x3C, 0xCA) + word(done) +
                listOf(0xAF, 0x32, 0x77, 0xC3) + word(link.partB)
        val partB =
            listOf(0x3E, if (kind == Talk.BATTLE) LINK_STATE_START_BATTLE else LINK_STATE_START_TRADE, 0xEA) + word(link.linkState) +
                listOf(0x06, 0x01, 0x21) + word(link.battleOrTrade) + call(link.bankswitch) +
                listOf(0xAF, 0xEA) + word(link.linkState) +
                listOf(0x21) + word(link.mapScript) + listOf(0x36, mapScript and 0xFF, 0x23, 0x36, mapScript shr 8) +
                listOf(0x06, 0x01, 0x21) + word(link.closeLink) + call(link.bankswitch) +
                listOf(0xC3) + word(link.playDefaultMusic)
        check(partA.size <= PART_A_SIZE && partB.size <= PART_B_SIZE && partB[PART_B_DONE] == 0xAF)
        return ByteArray(partA.size) { partA[it].toByte() } to ByteArray(partB.size) { partB[it].toByte() }
    }

    private inner class InPlaceRun(private val kind: String) : InPlaceSteps {
        @Volatile
        private var started = false

        // Starts once the player stands free on the map (no text box, no step under way).
        override fun step(
            memory: GameMemory,
            now: Long,
        ): String? {
            if (started) return null
            val link = layout.inPlace
            val free =
                memory.u8(ram(layout.isInBattle)) == 0 && (memory.u8(ram(layout.fontLoaded)) ?: 1) and 1 == 0 &&
                    memory.u8(ram(layout.walkCounter)) == 0 && memory.u8(ram(link.joyIgnore)) == 0 && memory.u8(ram(link.linkState)) == 0
            if (!free) return null
            val mapScript = memory.u16(ram(link.mapScript)) ?: return null
            if (mapScript == PART_A) return null
            val (partA, partB) = inPlaceRoutine(kind, mapScript)
            val patch =
                MemoryPatch(
                    listOf(
                        ram(PART_A) to partA,
                        ram(link.partB) to partB,
                        // Last: the map's script, which sets it off on the next frame.
                        ram(link.mapScript) to byteArrayOf(PART_A.toByte(), (PART_A shr 8).toByte()),
                    ),
                )
            if (!patch.applyTo(memory)) return "couldn't write the in-place routine"
            started = true
            return "in-place $kind routine started (map script was %04X)".format(mapScript)
        }

        // Going while the map's script is still ours or the game's link state is set.
        override fun running(memory: GameMemory): Boolean {
            if (!started) return false
            val link = layout.inPlace
            return memory.u16(ram(link.mapScript)) == PART_A || (memory.u8(ram(link.linkState)) ?: 0) != 0
        }
    }

    // The trade or battle has begun once the routine set the game's link state.
    override fun linkActive(memory: GameMemory): Boolean = (memory.u8(ram(layout.inPlace.linkState)) ?: 0) >= LINK_STATE_START_TRADE

    override fun scriptPending(memory: GameMemory): Boolean = memory.u16(ram(layout.inPlace.mapScript)) == PART_A

    override fun linkDebug(memory: GameMemory): String? {
        val link = layout.inPlace
        return "mapscript=${memory.u16(ram(link.mapScript))?.let { "%04X".format(it) }} link=${memory.u8(ram(link.linkState))} " +
            "font=${memory.u8(ram(layout.fontLoaded))} walk=${memory.u8(ram(layout.walkCounter))}"
    }

    override fun neighbors(memory: GameMemory): Map<String, MapOffset> {
        val flags = memory.u8(ram(layout.connectionFlags)) ?: return emptyMap()
        val width = (memory.u8(ram(layout.mapWidth)) ?: return emptyMap()) * 2
        val height = (memory.u8(ram(layout.mapHeight)) ?: return emptyMap()) * 2
        val result = HashMap<String, MapOffset>()
        // North, south, west, east; flag bits 3, 2, 1, 0.
        for (side in 0 until 4) {
            if (flags and (8 shr side) == 0) continue
            val base = layout.northConnection + side * CONNECTION_SIZE
            val map = memory.u8(ram(base)) ?: continue
            if (map == 0xFF) continue
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
            result["$MAP_GROUP.$map"] = MapOffset(dx * 16, dy * 16)
        }
        return result
    }

    private val places = ConcurrentHashMap<String, Place>()

    // The town map's name for the map: towns and routes by number, the rest by indoor group.
    override fun place(map: String): Place? {
        places[map]?.let { return it }
        val place =
            runCatching {
                val (group, number) = map.split('.').map { it.toInt() }
                if (group != MAP_GROUP || number !in 0..0xF7) return null
                val pointer =
                    if (number < FIRST_INDOOR_MAP) {
                        rom.u16(layout.outdoorNames + number * 3L + 1) ?: return null
                    } else {
                        var entry = layout.indoorNames.toLong()
                        var found: Int? = null
                        for (i in 0 until 64) {
                            val end = rom.u8(entry) ?: return null
                            if (number < end) {
                                found = rom.u16(entry + 2)
                                break
                            }
                            entry += 4
                        }
                        found ?: return null
                    }
                val name = decodeText(romOffset(layout.namesBank, pointer)) ?: return null
                Place(titleCase(name), number >= FIRST_INDOOR_MAP)
            }.getOrNull() ?: return null
        places[map] = place
        return place
    }

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
                0xBA -> text.append('é')
                else -> text.append(' ')
            }
        }
        return text.toString().trim().ifEmpty { null }
    }

    override fun sprite(look: Look): SpriteImage? {
        val colors = colorsPointer
        val key = (colors.toLong() shl 24) or (look.sprite.toLong() shl 8) or look.frame.toLong()
        pictures[key]?.let { return it }
        if (key in missing) return null
        val picture = runCatching { draw(look.sprite, look.frame, colors) }.getOrNull()
        if (picture == null) missing += key else pictures[key] = picture
        return picture
    }

    private fun draw(
        picture: Int,
        frame: Int,
        colors: Int,
    ): SpriteImage? {
        if (picture == 0 || frame !in 0 until 16) return null
        val entry = layout.spriteSheets + (picture - 1) * 4L
        val pointer = rom.u16(entry) ?: return null
        val size = rom.u8(entry + 2) ?: return null
        val bank = rom.u8(entry + 3) ?: return null
        if (pointer !in 0x4000..0x7FFF || size == 0) return null
        val graphics = romOffset(bank, pointer)
        val palette = palette(colors)

        // Characters that only stand (items, boulders...) have one frame.
        val walks = size >= 12 * TILE_BYTES
        val (tiles, flipped) = if (walks) FRAMES[frame] else FRAMES[0]
        val pixels = IntArray(16 * 16)
        for (piece in 0 until 4) {
            val tile = tiles[piece]
            // Walking tiles ($80+) follow the 12 standing ones.
            val index = if (tile >= 0x80) 12 + (tile - 0x80) else tile
            // The pieces go top left, top right, bottom left, bottom right; mirrored, left and right swap.
            val pieceX = (if (flipped) 1 - piece % 2 else piece % 2) * 8
            val pieceY = piece / 2 * 8
            for (row in 0 until 8) {
                val low = rom.u8(graphics + index * TILE_BYTES.toLong() + row * 2) ?: return null
                val high = rom.u8(graphics + index * TILE_BYTES.toLong() + row * 2 + 1) ?: return null
                for (column in 0 until 8) {
                    val bit = 7 - column
                    val color = ((low shr bit) and 1) or (((high shr bit) and 1) shl 1)
                    if (color == 0) continue
                    val px = pieceX + if (flipped) 7 - column else column
                    pixels[(pieceY + row) * 16 + px] = palette[color]
                }
            }
        }
        return SpriteImage(16, 16, pixels)
    }

    // The overworld's character colors (OBP0 = %11010000: colors 1, 2, 3 are shades 0, 1, 3), from
    // the map's Game Boy Color palette in Yellow, else the Game Boy Pocket's greys (the app's default).
    private fun palette(colors: Int): IntArray {
        val shades = IntArray(4) { POCKET[it] }
        if (layout.cgbBasePalPointers != 0 && colors in 0x4000..0x7FF8) {
            val base = romOffset(layout.colorsBank, colors).toLong()
            for (shade in 0 until 4) rom.u16(base + shade * 2)?.let { shades[shade] = bgr555ToArgb(it) }
        }
        return IntArray(4) { color -> shades[(OBP0 shr (2 * color)) and 3] }
    }

    private fun romOffset(
        bank: Int,
        address: Int,
    ): Int = if (address < 0x4000) address else bank * 0x4000 + (address - 0x4000)

    companion object {
        // Gen 1 has no map groups: every map is in one.
        private const val MAP_GROUP = 1
        private const val FIRST_INDOOR_MAP = 0x25

        // wSpritePlayerStateData1: the player's character.
        private const val PLAYER = 0xC100
        private const val SPRITE_PICTURE = 0
        private const val SPRITE_IMAGE = 2
        private const val SPRITE_Y_STEP = 3
        private const val SPRITE_SCREEN_Y = 4
        private const val SPRITE_X_STEP = 5
        private const val SPRITE_SCREEN_X = 6

        private const val CONNECTION_SIZE = 11
        private const val CONNECTION_Y_ALIGN = 7
        private const val CONNECTION_X_ALIGN = 8
        private const val TILE_BYTES = 16
        private const val OBP0 = 0xD0

        // The in-place routine: part A's place (its first byte is the link core's friend meeting),
        // part B's "done" (its link core failure exit), and what both need.
        const val PART_A = 0xCC5F
        private const val PART_A_SIZE = 166
        private const val PART_B_SIZE = 78
        private const val PART_B_DONE = 13
        private const val SAVE_BANK = 0x1C
        private const val LINK_TIMEOUT_FRAMES = 0xCC47
        private const val EXCHANGE_NYBBLE_SEND = 0xCC42
        private const val LINK_STATE_START_TRADE = 2
        private const val LINK_STATE_START_BATTLE = 3

        private val FACINGS = arrayOf(Facing.DOWN, Facing.UP, Facing.LEFT, Facing.RIGHT)

        // SpriteFacingAndAnimationTable: the 4 tiles of each frame, and whether it's mirrored.
        private val STANDING_DOWN = intArrayOf(0x00, 0x01, 0x02, 0x03)
        private val WALKING_DOWN = intArrayOf(0x80, 0x81, 0x82, 0x83)
        private val STANDING_UP = intArrayOf(0x04, 0x05, 0x06, 0x07)
        private val WALKING_UP = intArrayOf(0x84, 0x85, 0x86, 0x87)
        private val STANDING_LEFT = intArrayOf(0x08, 0x09, 0x0A, 0x0B)
        private val WALKING_LEFT = intArrayOf(0x88, 0x89, 0x8A, 0x8B)
        private val FRAMES =
            arrayOf(
                STANDING_DOWN to false, WALKING_DOWN to false, STANDING_DOWN to false, WALKING_DOWN to true,
                STANDING_UP to false, WALKING_UP to false, STANDING_UP to false, WALKING_UP to true,
                STANDING_LEFT to false, WALKING_LEFT to false, STANDING_LEFT to false, WALKING_LEFT to false,
                STANDING_LEFT to true, WALKING_LEFT to true, STANDING_LEFT to true, WALKING_LEFT to true,
            )

        // The Game Boy Pocket's four shades (gambatte's "GB - Pocket" palette), lightest first.
        private val POCKET = intArrayOf(0xFFA7B19A.toInt(), 0xFF86927C.toInt(), 0xFF535F49.toInt(), 0xFF2A3325.toInt())

        private val RED_BLUE_RAM =
            Layout(
                world = "kanto-gen1", curMap = 0xD35E, xCoord = 0xD362, yCoord = 0xD361, walkCounter = 0xCFC5,
                fontLoaded = 0xCFC4, isInBattle = 0xD057, mapHeight = 0xD368, mapWidth = 0xD369,
                connectionFlags = 0xD370, northConnection = 0xD371, playerName = 0xD158,
                cgbBasePalPointers = 0, colorsBank = 0,
                spriteSheets = 0x05 * 0x4000 + (0x7B27 - 0x4000),
                outdoorNames = 0x1C * 0x4000 + (0x5313 - 0x4000),
                indoorNames = 0x1C * 0x4000 + (0x5382 - 0x4000), namesBank = 0x1C,
                inPlace =
                    InPlace(
                        mapScript = 0xD36E, linkState = 0xD12B, joyIgnore = 0xCD6B, partB = 0xD66A,
                        sendZeroByte = 0x22ED, delayFrame = 0x20AF, delayFrames = 0x3739, bankswitch = 0x35D6,
                        syncAndExchangeNybble = 0x227F, playDefaultMusic = 0x2307,
                        saveGameData = 0x7848, battleOrTrade = 0x5317, closeLink = 0x72D7,
                    ),
            )
        private val YELLOW =
            Layout(
                world = "kanto-gen1", curMap = 0xD35D, xCoord = 0xD361, yCoord = 0xD360, walkCounter = 0xCFC4,
                fontLoaded = 0xCFC3, isInBattle = 0xD056, mapHeight = 0xD367, mapWidth = 0xD368,
                connectionFlags = 0xD36F, northConnection = 0xD370, playerName = 0xD157,
                cgbBasePalPointers = 0xDEE1, colorsBank = 0x1C,
                spriteSheets = 0x05 * 0x4000 + (0x42A9 - 0x4000),
                outdoorNames = 0x1C * 0x4000 + (0x539C - 0x4000),
                indoorNames = 0x1C * 0x4000 + (0x540B - 0x4000), namesBank = 0x1C,
                inPlace =
                    InPlace(
                        mapScript = 0xD36D, linkState = 0xD12A, joyIgnore = 0xCD6B, partB = 0xD669,
                        sendZeroByte = 0x2149, delayFrame = 0x1E64, delayFrames = 0x372F, bankswitch = 0x3E84,
                        syncAndExchangeNybble = 0x20DB, playDefaultMusic = 0x216B,
                        saveGameData = 0x7B91, battleOrTrade = 0x53A5, closeLink = 0x71AC,
                    ),
            )

        /**
         * The layout for a Red, Blue or Yellow ROM by its header title and global checksum (the
         * English releases these addresses are for), or null.
         */
        fun layoutFor(
            title: String,
            checksum: Int,
        ): Layout? =
            when {
                title.startsWith("POKEMON RED") && checksum == 0x91E6 -> RED_BLUE_RAM
                title.startsWith("POKEMON BLUE") && checksum == 0x9D0A -> RED_BLUE_RAM
                title.startsWith("POKEMON YELLOW") && checksum == 0x047C -> YELLOW
                else -> null
            }
    }
}
