package com.swordfish.lemuroid.app.shared.online.world

/**
 * Knows one game version well enough to share its world: where our character is, and how to
 * draw other players' characters with this game's own graphics (read from the player's ROM).
 */
interface WorldAdapter {
    /** Players see each other only inside the same world, for example "hoenn" or "johto". */
    val world: String

    /** Size of the game's screen in pixels. */
    val screenWidth: Int
    val screenHeight: Int

    /** Our character right now, or null when we aren't walking around a map. */
    fun read(memory: GameMemory): Avatar?

    /** The picture for [look] in this game, or null when this game can't draw it. */
    fun sprite(look: Look): SpriteImage?
}

/** How a character looks on one frame: which sprite sheet, which frame, mirrored or not. */
data class Look(val sprite: Int, val frame: Int, val flip: Boolean)

/**
 * A character in the world.
 *
 * [x]/[y]: position in pixels on [map], the same way for every player of this world, so two
 * players' positions can be compared. [screenX]/[screenY]: top-left of our own sprite on the
 * game screen (only used for our own character). [covered]: a menu or text box is open on our
 * screen, so others aren't drawn on top of it (we still stand on the map for them).
 */
data class Avatar(
    val map: String,
    val x: Int,
    val y: Int,
    val look: Look,
    val screenX: Int = 0,
    val screenY: Int = 0,
    val covered: Boolean = false,
)

/** A small ARGB picture; transparent pixels are 0. */
class SpriteImage(val width: Int, val height: Int, val pixels: IntArray)

/** Little-endian reads from a ROM file, addressed the way the console sees the cartridge. */
class Rom(val bytes: ByteArray, private val base: Long) {
    fun u8(address: Long): Int? = index(address, 1)?.let { bytes[it].toInt() and 0xFF }

    fun u16(address: Long): Int? = index(address, 2)?.let { (bytes[it].toInt() and 0xFF) or ((bytes[it + 1].toInt() and 0xFF) shl 8) }

    fun s16(address: Long): Int? = u16(address)?.let { if (it >= 0x8000) it - 0x10000 else it }

    fun u32(address: Long): Long? {
        val low = u16(address) ?: return null
        val high = u16(address + 2) ?: return null
        return low.toLong() or (high.toLong() shl 16)
    }

    fun text(
        offset: Int,
        length: Int,
    ): String = String(bytes, offset, length, Charsets.US_ASCII)

    private fun index(
        address: Long,
        length: Int,
    ): Int? {
        val offset = address - base
        return if (offset >= 0 && offset + length <= bytes.size) offset.toInt() else null
    }
}

/** Converts a 15-bit console color (BGR555) to ARGB. */
fun bgr555ToArgb(color: Int): Int {
    val r = (color and 31) * 255 / 31
    val g = ((color shr 5) and 31) * 255 / 31
    val b = ((color shr 10) and 31) * 255 / 31
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
