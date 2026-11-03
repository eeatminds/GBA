package com.swordfish.lemuroid.app.shared.online.world

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sign

/**
 * Ruby, Sapphire, Emerald, FireRed and LeafGreen share one overworld engine. Addresses come
 * from the pret decompilation projects (pokeruby, pokeemerald, pokefirered), one set per
 * game version.
 *
 * Position: the save block's `pos` is the tile the camera is on (it moves to the next tile as
 * soon as a step starts), and the camera object's x/y (-15..15) is how far the view has
 * scrolled through that step. That gives every player's position to the pixel, the same way
 * the game itself places characters (SetSpritePosToMapCoords).
 */
class Gen3Adapter(
    private val rom: Rom,
    private val layout: Layout,
) : WorldAdapter {
    data class Layout(
        val world: String,
        val main: Long,
        val cb2Overworld: Long,
        // Either the save block itself (Ruby/Sapphire) or a pointer to it.
        val saveBlock1: Long,
        val saveBlock1IsPointer: Boolean,
        val playerAvatar: Long,
        val objectEvents: Long,
        val sprites: Long,
        val spriteCoordOffsetX: Long,
        val spriteCoordOffsetY: Long,
        val fieldCamera: Long,
        // Set while the start menu, a text box or a script holds the player.
        val fieldControlsLocked: Long,
        val graphicsInfoPointers: Long,
        val graphicsInfoCount: Int,
        val spritePalettes: Long,
        val spritePaletteCount: Int,
        // The current map's header (a RAM copy), and in ROM every map's header and the names
        // of the town map's sections.
        val mapHeader: Long,
        val mapGroups: Long,
        val mapNames: MapNames,
    )

    /**
     * Ruby, Sapphire and Emerald keep names in their region map entries (8 bytes, the name
     * pointer last); FireRed and LeafGreen in a table of name pointers that starts at Kanto's
     * first section.
     */
    data class MapNames(val table: Long, val entrySize: Int, val pointerOffset: Int, val firstSection: Int)

    override val world: String get() = layout.world
    override val screenWidth: Int = 240
    override val screenHeight: Int = 160

    private val pictures = ConcurrentHashMap<Long, SpriteImage>()
    private val missing = ConcurrentHashMap.newKeySet<Long>()

    override fun read(memory: GameMemory): Avatar? {
        // Only while the overworld runs (not in battles, menus that replace the map, etc.).
        if (memory.u32(layout.main + 4) != (layout.cb2Overworld or 1L)) return null

        val saveBlock = if (layout.saveBlock1IsPointer) memory.u32(layout.saveBlock1) ?: return null else layout.saveBlock1
        if (saveBlock !in EWRAM) return null
        val tileX = memory.s16(saveBlock) ?: return null
        val tileY = memory.s16(saveBlock + 2) ?: return null
        val mapGroup = memory.u8(saveBlock + 4) ?: return null
        val mapNum = memory.u8(saveBlock + 5) ?: return null

        val scrollX = memory.s32(layout.fieldCamera + CAMERA_X) ?: return null
        val scrollY = memory.s32(layout.fieldCamera + CAMERA_Y) ?: return null
        if (abs(scrollX) > 15 || abs(scrollY) > 15) return null

        val spriteId = memory.u8(layout.playerAvatar + AVATAR_SPRITE_ID) ?: return null
        val objectId = memory.u8(layout.playerAvatar + AVATAR_OBJECT_ID) ?: return null
        if (spriteId >= MAX_SPRITES || objectId >= MAX_OBJECTS) return null

        val sprite = layout.sprites + spriteId * SPRITE_SIZE
        val flags = memory.u8(sprite + SPRITE_FLAGS) ?: return null
        if (flags and FLAG_IN_USE == 0 || flags and FLAG_INVISIBLE != 0) return null

        val graphicsId = memory.u8(layout.objectEvents + objectId * OBJECT_SIZE + OBJECT_GRAPHICS_ID) ?: return null
        if (graphicsId >= layout.graphicsInfoCount) return null

        val coordOffsetX = if (flags and FLAG_COORD_OFFSET != 0) memory.s16(layout.spriteCoordOffsetX) ?: 0 else 0
        val coordOffsetY = if (flags and FLAG_COORD_OFFSET != 0) memory.s16(layout.spriteCoordOffsetY) ?: 0 else 0
        val screenX =
            (memory.s16(sprite + SPRITE_X) ?: return null) + (memory.s16(sprite + SPRITE_X2) ?: 0) +
                (memory.s8(sprite + SPRITE_CENTER_TO_CORNER_X) ?: 0) + coordOffsetX
        val screenY =
            (memory.s16(sprite + SPRITE_Y) ?: return null) + (memory.s16(sprite + SPRITE_Y2) ?: 0) +
                (memory.s8(sprite + SPRITE_CENTER_TO_CORNER_Y) ?: 0) + coordOffsetY

        val facing =
            when ((memory.u8(layout.objectEvents + objectId * OBJECT_SIZE + OBJECT_FACING) ?: 0) and 0x0F) {
                DIR_SOUTH -> Facing.DOWN
                DIR_NORTH -> Facing.UP
                DIR_WEST -> Facing.LEFT
                DIR_EAST -> Facing.RIGHT
                else -> null
            }

        val frame = currentFrame(memory, sprite)
        // The live mirroring is in the OAM attributes (bit 28); right-facing frames are left ones mirrored.
        val flip = ((memory.u32(sprite) ?: 0L) shr OAM_H_FLIP_BIT) and 1L == 1L

        return Avatar(
            map = "$mapGroup.$mapNum",
            x = tileX * 16 + scrollX - 16 * scrollX.sign,
            y = tileY * 16 + scrollY - 16 * scrollY.sign,
            look = Look(graphicsId, frame, flip),
            screenX = screenX,
            screenY = screenY,
            covered = (memory.u8(layout.fieldControlsLocked) ?: 0) != 0,
            facing = facing,
        )
    }

    /** From the current map's connections (in ROM): each joined map, and how it lines up. */
    override fun neighbors(memory: GameMemory): Map<String, MapOffset> {
        val connections = memory.u32(layout.mapHeader + HEADER_CONNECTIONS) ?: return emptyMap()
        if (connections == 0L) return emptyMap()
        val ourLayout = memory.u32(layout.mapHeader + HEADER_LAYOUT) ?: return emptyMap()
        val ourWidth = rom.u32(ourLayout + LAYOUT_WIDTH)?.toInt() ?: return emptyMap()
        val ourHeight = rom.u32(ourLayout + LAYOUT_HEIGHT)?.toInt() ?: return emptyMap()
        val count = rom.u32(connections)?.toInt() ?: return emptyMap()
        val list = rom.u32(connections + 4) ?: return emptyMap()
        if (count !in 0..MAX_CONNECTIONS) return emptyMap()
        val result = HashMap<String, MapOffset>()
        for (i in 0 until count) {
            val entry = list + i * CONNECTION_SIZE
            val direction = rom.u8(entry) ?: continue
            val offset = rom.u32(entry + CONNECTION_OFFSET)?.toInt() ?: continue
            val group = rom.u8(entry + CONNECTION_GROUP) ?: continue
            val number = rom.u8(entry + CONNECTION_NUMBER) ?: continue
            val header = mapHeader(group, number) ?: continue
            val theirLayout = rom.u32(header + HEADER_LAYOUT) ?: continue
            val theirWidth = rom.u32(theirLayout + LAYOUT_WIDTH)?.toInt() ?: continue
            val theirHeight = rom.u32(theirLayout + LAYOUT_HEIGHT)?.toInt() ?: continue
            // Where their (0, 0) tile is on our map.
            val (dx, dy) =
                when (direction) {
                    CONNECTION_SOUTH -> offset to ourHeight
                    CONNECTION_NORTH -> offset to -theirHeight
                    CONNECTION_WEST -> -theirWidth to offset
                    CONNECTION_EAST -> ourWidth to offset
                    // Diving and surfacing aren't side by side.
                    else -> continue
                }
            result["$group.$number"] = MapOffset(dx * 16, dy * 16)
        }
        return result
    }

    private fun mapHeader(
        group: Int,
        number: Int,
    ): Long? {
        val maps = rom.u32(layout.mapGroups + group * 4L) ?: return null
        return rom.u32(maps + number * 4L)
    }

    private val places = ConcurrentHashMap<String, Place>()

    override fun place(map: String): Place? {
        places[map]?.let { return it }
        val place =
            runCatching {
                val (group, number) = map.split('.').map { it.toInt() }
                val header = mapHeader(group, number) ?: return null
                val section = rom.u8(header + HEADER_REGION_MAP_SECTION) ?: return null
                val mapType = rom.u8(header + HEADER_MAP_TYPE) ?: return null
                val names = layout.mapNames
                if (section < names.firstSection) return null
                val pointer = rom.u32(names.table + (section - names.firstSection) * names.entrySize.toLong() + names.pointerOffset) ?: return null
                val name = decodeText(pointer) ?: return null
                Place(titleCase(name), mapType == MAP_TYPE_INDOOR)
            }.getOrNull() ?: return null
        places[map] = place
        return place
    }

    // The game's own text encoding (letters, digits and a few signs are enough for place names).
    private fun decodeText(address: Long): String? {
        val text = StringBuilder()
        for (i in 0 until 32) {
            val c = rom.u8(address + i) ?: return null
            when (c) {
                0xFF -> break
                in 0xBB..0xD4 -> text.append('A' + (c - 0xBB))
                in 0xD5..0xEE -> text.append('a' + (c - 0xD5))
                in 0xA1..0xAA -> text.append('0' + (c - 0xA1))
                0x1B -> text.append('é')
                0xAD -> text.append('.')
                0xAE -> text.append('-')
                0xB4 -> text.append('\'')
                else -> text.append(' ')
            }
        }
        return text.toString().trim().ifEmpty { null }
    }

    // The picture the sprite shows now: its animation's current frame command (in ROM).
    private fun currentFrame(
        memory: GameMemory,
        sprite: Long,
    ): Int {
        val anims = memory.u32(sprite + SPRITE_ANIMS) ?: return 0
        val animNum = memory.u8(sprite + SPRITE_ANIM_NUM) ?: return 0
        val cmdIndex = memory.u8(sprite + SPRITE_ANIM_CMD_INDEX) ?: return 0
        val animation = rom.u32(anims + animNum * 4L) ?: return 0
        val command = rom.u32(animation + cmdIndex * 4L) ?: return 0
        val image = (command and 0xFFFF).toInt()
        // 0xFFFD..0xFFFF are loop/jump/end commands, not pictures.
        return if (image >= 0xFFFD) 0 else image
    }

    override fun sprite(look: Look): SpriteImage? {
        val key = (look.sprite.toLong() shl 16) or look.frame.toLong()
        pictures[key]?.let { return it }
        if (key in missing) return null
        val picture = runCatching { decode(look.sprite, look.frame) }.getOrNull()
        if (picture == null) missing += key else pictures[key] = picture
        return picture
    }

    private fun decode(
        graphicsId: Int,
        frame: Int,
    ): SpriteImage? {
        if (graphicsId !in 0 until layout.graphicsInfoCount) return null
        val info = rom.u32(layout.graphicsInfoPointers + graphicsId * 4L) ?: return null
        val paletteTag = rom.u16(info + INFO_PALETTE_TAG) ?: return null
        val width = rom.s16(info + INFO_WIDTH) ?: return null
        val height = rom.s16(info + INFO_HEIGHT) ?: return null
        if (width !in 8..64 || height !in 8..64) return null
        val images = rom.u32(info + INFO_IMAGES) ?: return null
        val data = rom.u32(images + frame * 8L) ?: return null
        val size = rom.u16(images + frame * 8L + 4) ?: return null
        if (size != width * height / 2) return null
        val palette = palette(paletteTag) ?: return null

        // 4 bits per pixel, in 8x8 tiles laid out row by row.
        val pixels = IntArray(width * height)
        val tilesPerRow = width / 8
        for (tile in 0 until size / 32) {
            val tileX = (tile % tilesPerRow) * 8
            val tileY = (tile / tilesPerRow) * 8
            for (p in 0 until 64) {
                val byte = rom.u8(data + tile * 32 + p / 2) ?: return null
                val color = if (p and 1 == 1) byte shr 4 else byte and 15
                if (color != 0) pixels[(tileY + p / 8) * width + tileX + p % 8] = palette[color]
            }
        }
        return SpriteImage(width, height, pixels)
    }

    private fun palette(tag: Int): IntArray? {
        for (i in 0 until layout.spritePaletteCount) {
            val entry = layout.spritePalettes + i * 8L
            val data = rom.u32(entry) ?: return null
            if (data == 0L) return null
            if (rom.u16(entry + 4) == tag) {
                return IntArray(16) { color -> bgr555ToArgb(rom.u16(data + color * 2L) ?: 0) }
            }
        }
        return null
    }

    companion object {
        private val EWRAM = 0x02000000L until 0x02040000L

        private const val CAMERA_X = 0x10L
        private const val CAMERA_Y = 0x14L

        private const val AVATAR_SPRITE_ID = 0x04L
        private const val AVATAR_OBJECT_ID = 0x05L

        private const val OBJECT_SIZE = 0x24L
        private const val OBJECT_GRAPHICS_ID = 0x05L
        private const val MAX_OBJECTS = 16

        private const val SPRITE_SIZE = 0x44L
        private const val MAX_SPRITES = 64
        private const val SPRITE_ANIMS = 0x08L
        private const val SPRITE_X = 0x20L
        private const val SPRITE_Y = 0x22L
        private const val SPRITE_X2 = 0x24L
        private const val SPRITE_Y2 = 0x26L
        private const val SPRITE_CENTER_TO_CORNER_X = 0x28L
        private const val SPRITE_CENTER_TO_CORNER_Y = 0x29L
        private const val SPRITE_ANIM_NUM = 0x2AL
        private const val SPRITE_ANIM_CMD_INDEX = 0x2BL
        private const val SPRITE_FLAGS = 0x3EL
        private const val FLAG_IN_USE = 0x1
        private const val FLAG_COORD_OFFSET = 0x2
        private const val FLAG_INVISIBLE = 0x4
        private const val OAM_H_FLIP_BIT = 28

        private const val OBJECT_FACING = 0x18L
        private const val DIR_SOUTH = 1
        private const val DIR_NORTH = 2
        private const val DIR_WEST = 3
        private const val DIR_EAST = 4

        private const val HEADER_LAYOUT = 0x00L
        private const val HEADER_CONNECTIONS = 0x0CL
        private const val HEADER_REGION_MAP_SECTION = 0x14L
        private const val HEADER_MAP_TYPE = 0x17L
        private const val LAYOUT_WIDTH = 0x00L
        private const val LAYOUT_HEIGHT = 0x04L
        private const val CONNECTION_SIZE = 12L
        private const val CONNECTION_OFFSET = 4L
        private const val CONNECTION_GROUP = 8L
        private const val CONNECTION_NUMBER = 9L
        private const val CONNECTION_SOUTH = 1
        private const val CONNECTION_NORTH = 2
        private const val CONNECTION_WEST = 3
        private const val CONNECTION_EAST = 4
        private const val MAX_CONNECTIONS = 16
        private const val MAP_TYPE_INDOOR = 8

        // Region map entries of Ruby, Sapphire and Emerald: x, y, width, height, name pointer.
        private fun regionMapEntries(table: Long) = MapNames(table, entrySize = 8, pointerOffset = 4, firstSection = 0)

        // FireRed and LeafGreen: name pointers from MAPSEC_PALLET_TOWN (0x58) on.
        private fun kantoNames(table: Long) = MapNames(table, entrySize = 4, pointerOffset = 0, firstSection = 0x58)

        private const val INFO_PALETTE_TAG = 0x02L
        private const val INFO_WIDTH = 0x08L
        private const val INFO_HEIGHT = 0x0AL
        private const val INFO_IMAGES = 0x1CL

        private fun firered(
            world: String,
            cb2Overworld: Long,
            graphicsInfoPointers: Long,
            spritePalettes: Long,
            mapGroups: Long,
            mapNames: Long,
        ) = Layout(
            world = world,
            main = 0x030030f0,
            cb2Overworld = cb2Overworld,
            saveBlock1 = 0x03005008,
            saveBlock1IsPointer = true,
            playerAvatar = 0x02037078,
            objectEvents = 0x02036e38,
            sprites = 0x0202063c,
            spriteCoordOffsetX = 0x02021bc8,
            spriteCoordOffsetY = 0x02021bca,
            fieldCamera = 0x03005050,
            fieldControlsLocked = 0x03000f9c,
            graphicsInfoPointers = graphicsInfoPointers,
            graphicsInfoCount = 0x260 / 4,
            spritePalettes = spritePalettes,
            spritePaletteCount = 0x98 / 8,
            mapHeader = 0x02036dfc,
            mapGroups = mapGroups,
            mapNames = kantoNames(mapNames),
        )

        private fun ruby(
            cb2Overworld: Long,
            graphicsInfoPointers: Long,
            spritePalettes: Long,
            mapGroups: Long,
            regionMapEntries: Long,
        ) = Layout(
            world = "hoenn-rs",
            main = 0x03001770,
            cb2Overworld = cb2Overworld,
            saveBlock1 = 0x02025734,
            saveBlock1IsPointer = false,
            playerAvatar = 0x0202e858,
            objectEvents = 0x030048a0,
            sprites = 0x02020004,
            spriteCoordOffsetX = 0x030024d0,
            spriteCoordOffsetY = 0x030027e0,
            fieldCamera = 0x03004880,
            fieldControlsLocked = 0x030006a4,
            graphicsInfoPointers = graphicsInfoPointers,
            graphicsInfoCount = 0x368 / 4,
            spritePalettes = spritePalettes,
            spritePaletteCount = 0xe0 / 8,
            mapHeader = 0x0202e828,
            mapGroups = mapGroups,
            mapNames = regionMapEntries(regionMapEntries),
        )

        val EMERALD =
            Layout(
                world = "hoenn-e",
                main = 0x030022c0,
                cb2Overworld = 0x08085e5c,
                saveBlock1 = 0x03005d8c,
                saveBlock1IsPointer = true,
                playerAvatar = 0x02037590,
                objectEvents = 0x02037350,
                sprites = 0x02020630,
                spriteCoordOffsetX = 0x02021bbc,
                spriteCoordOffsetY = 0x02021bbe,
                fieldCamera = 0x03005dd0,
                fieldControlsLocked = 0x03000f2c,
                graphicsInfoPointers = 0x08505620,
                graphicsInfoCount = 0x3bc / 4,
                spritePalettes = 0x0850bbc8,
                spritePaletteCount = 0x120 / 8,
                mapHeader = 0x02037318,
                mapGroups = 0x08486578,
                mapNames = regionMapEntries(0x085a147c),
            )

        val FIRERED_10 = firered("kanto-frlg", 0x080565b4, 0x0839fdb0, 0x083a5158, 0x083526a8, 0x083f1cac)
        val FIRERED_11 = firered("kanto-frlg", 0x080565c8, 0x0839fe20, 0x083a51c8, 0x08352718, 0x083f1d1c)
        val LEAFGREEN_10 = firered("kanto-frlg", 0x080565b4, 0x0839fd90, 0x083a5138, 0x08352688, 0x083f1ae8)
        val LEAFGREEN_11 = firered("kanto-frlg", 0x080565c8, 0x0839fe00, 0x083a51a8, 0x083526f8, 0x083f1b58)
        val RUBY_10 = ruby(0x080543a4, 0x0836dc58, 0x0837377c, 0x08308588, 0x083e73c4)
        val RUBY_11 = ruby(0x080543c4, 0x0836dc70, 0x08373794, 0x083085a0, 0x083e73e0)
        val SAPPHIRE_10 = ruby(0x080543a8, 0x0836dbe8, 0x0837370c, 0x08308518, 0x083e741c)
        val SAPPHIRE_11 = ruby(0x080543c8, 0x0836dc00, 0x08373724, 0x08308530, 0x083e743c)

        /** The layout for a GBA ROM, from its header's game code and version, or null. */
        fun layoutFor(
            gameCode: String,
            version: Int,
        ): Layout? =
            when (gameCode) {
                "BPEE" -> EMERALD.takeIf { version == 0 }
                "BPRE" -> if (version == 0) FIRERED_10 else if (version == 1) FIRERED_11 else null
                "BPGE" -> if (version == 0) LEAFGREEN_10 else if (version == 1) LEAFGREEN_11 else null
                // Ruby and Sapphire 1.1 and 1.2 use the same addresses.
                "AXVE" -> if (version == 0) RUBY_10 else if (version in 1..2) RUBY_11 else null
                "AXPE" -> if (version == 0) SAPPHIRE_10 else if (version in 1..2) SAPPHIRE_11 else null
                else -> null
            }
    }
}
