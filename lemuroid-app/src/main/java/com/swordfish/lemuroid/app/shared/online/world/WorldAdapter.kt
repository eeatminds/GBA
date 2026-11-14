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

    /**
     * The maps joined to the one we're on now (walk off its edge and you're there), with where
     * each sits on ours: add the offset to a position on that map to get one on ours.
     */
    fun neighbors(memory: GameMemory): Map<String, MapOffset> = emptyMap()

    /** Where [map] is, as the game's own town map names it, or null when unknown. */
    fun place(map: String): Place? = null

    /**
     * Memory writes that make the game run its own link trade ([Talk.TRADE]) or battle
     * ([Talk.BATTLE]) right where the player stands, or null when this game can't.
     */
    fun inPlaceLink(kind: String): MemoryPatch? = null

    /**
     * For games whose in-place link goes step by step (GBA): the steps of one, which [WorldSession]
     * runs on the emulator thread on every tick while it goes. Null when [inPlaceLink] does it all.
     */
    fun inPlaceSteps(
        kind: String,
        now: Long,
    ): InPlaceSteps? = null

    /** Whether such a link session (its script, the trade or battle screens) still runs. */
    fun inPlaceLinkRunning(memory: GameMemory): Boolean = false

    /** Memory writes that close the link afterwards, as the game does when leaving its link rooms. */
    fun inPlaceLinkCleanup(): MemoryPatch? = null

    // Joining the host's world, and coming back home with what was traded (see WorldVisit).

    /** Memory writes that take our character to step ([x], [y]) on [map], or null when this game can't. */
    fun visitWarp(
        map: String,
        x: Int,
        y: Int,
    ): MemoryPatch? = null

    /** The trainer name in the running game, while on a map, or null when this game can't tell. */
    fun trainerName(memory: GameMemory): String? = null

    /** The Pokémon in our party, in order, or null when this game can't tell. */
    fun party(memory: GameMemory): List<PartyMon>? = null

    /** Memory writes that make [party] our party. */
    fun writeParty(party: List<PartyMon>): MemoryPatch? = null

    /** Memory writes that mark [species] (national numbers) as caught and seen in the Pokédex. */
    fun registerInPokedex(
        memory: GameMemory,
        species: List<Int>,
    ): MemoryPatch? = null

    /** Memory writes that make the game save itself, without asking anything. */
    fun saveGame(): MemoryPatch? = null

    /** The game's link-related state as a short line, for the link report. */
    fun linkDebug(memory: GameMemory): String? = null

    /** Whether the game's link (for a trade or a battle) is on. */
    fun linkActive(memory: GameMemory): Boolean = false

    /** Whether a script we queued hasn't finished yet. */
    fun scriptPending(memory: GameMemory): Boolean = false
}

/**
 * One Pokémon in a party. [key] says who it is and stays the same when it evolves; [species]
 * is the number the party list shows; [data] is everything the game keeps for it.
 */
class PartyMon(val key: String, val species: Int, val data: ByteArray)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Bytes to write into the game's memory, in order (the last write sets things off). */
class MemoryPatch(val writes: List<Pair<Long, ByteArray>>) {
    fun applyTo(memory: GameMemory): Boolean = writes.all { (address, bytes) -> memory.write(address, bytes) }
}

/** An in-place link that goes step by step; see [WorldAdapter.inPlaceSteps]. */
interface InPlaceSteps {
    /** Writes what the game needs next. Returns what it did, for the link report, or null. */
    fun step(
        memory: GameMemory,
        now: Long,
    ): String?

    /** Whether it has begun and still goes (the game isn't back on its normal, free map). */
    fun running(memory: GameMemory): Boolean
}

/** Pixels to add to a position on a neighbouring map to place it on ours. */
data class MapOffset(val dx: Int, val dy: Int)

/** A map's town or route name ("New Bark Town"), and whether the map is inside a building. */
data class Place(val name: String, val indoors: Boolean)

/** Which way a character looks. */
enum class Facing(val dx: Int, val dy: Int) {
    DOWN(0, 1),
    UP(0, -1),
    LEFT(-1, 0),
    RIGHT(1, 0),
}

/** Shared worlds. Game titles are never shown: players only see "another game". */
object Worlds {
    // A player whose game can't be shown on the map still says so, for the Players panel.
    const val NONE = "none"

    @Suppress("UNUSED_PARAMETER")
    fun gameName(world: String): String? = null
}

/** "NEW BARK TOWN" -> "New Bark Town", "MT. MOON" -> "Mt. Moon", "ROUTE 29" stays readable. */
fun titleCase(text: String): String =
    text.trim().split(Regex("\\s+")).joinToString(" ") { word ->
        if (word.any { it.isLowerCase() }) word else word.lowercase().replaceFirstChar { it.uppercase() }
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
    // Which way our own character looks (to know who we're talking to); not shared.
    val facing: Facing? = null,
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
