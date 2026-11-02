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
        // ROM offsets.
        val facings: Int,
        val overworldSprites: Int,
        val objectPalettes: Int,
    )

    override val world: String get() = layout.world
    override val screenWidth: Int = 160
    override val screenHeight: Int = 144

    private val pictures = ConcurrentHashMap<Long, SpriteImage>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    // Bank 0 ($C000) and bank 1 ($D000) sit one after the other in the work RAM block.
    private fun ram(address: Int): Long = GameMemory.SYSTEM_RAM_BASE + (address - 0xC000)

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
        )
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
                facings = romOffset(0x01, 0x4049),
                overworldSprites = romOffset(0x05, 0x4736),
                objectPalettes = romOffset(0x02, 0x7469),
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
                facings = romOffset(0x01, 0x404A),
                overworldSprites = romOffset(0x05, 0x47DE),
                objectPalettes = romOffset(0x02, 0x78AE),
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
