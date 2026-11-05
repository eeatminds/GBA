package com.swordfish.lemuroid.app.shared.online.world

/**
 * The trainer name a player gave in their game, read from the game's own save, so rooms show
 * "GOLD" or "Misty" instead of "Player 1". Only for the games the world readers know.
 */
object TrainerNames {
    fun fromSave(
        systemId: String,
        rom: ByteArray,
        save: ByteArray,
    ): String? =
        runCatching {
            when (systemId) {
                "gb", "gbc" -> gen2(rom, save)
                "gba" -> gen3(rom, save)
                else -> null
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    // Gold, Silver and Crystal keep the player data in save bank 1 ($A009), after the 2-byte
    // trainer ID; the byte before it says the save is good (99).
    private fun gen2(
        rom: ByteArray,
        save: ByteArray,
    ): String? {
        if (rom.size < 0x150) return null
        val title = String(rom, 0x134, 11, Charsets.US_ASCII)
        val code = String(rom, 0x13F, 4, Charsets.US_ASCII)
        Gen2Adapter.layoutFor(title, code) ?: return null
        if (save.size < GEN2_NAME + GEN2_NAME_LENGTH || save[GEN2_CHECK].toInt() and 0xFF != GEN2_CHECK_VALUE) return null
        return gen2Text(save.copyOfRange(GEN2_NAME, GEN2_NAME + GEN2_NAME_LENGTH))
    }

    /** A Gold, Silver or Crystal name (letters, digits, a few marks; ends at $50), or null when it isn't one. */
    fun gen2Text(bytes: ByteArray): String? {
        val name = StringBuilder()
        for (byte in bytes) {
            when (val c = byte.toInt() and 0xFF) {
                0x50 -> return name.toString().trim().takeIf { it.isNotEmpty() }
                in 0x80..0x99 -> name.append('A' + (c - 0x80))
                in 0xA0..0xB9 -> name.append('a' + (c - 0xA0))
                in 0xF6..0xFF -> name.append('0' + (c - 0xF6))
                0x7F -> name.append(' ')
                0xE3 -> name.append('-')
                0xE8 -> name.append('.')
                else -> return null
            }
        }
        return null
    }

    // Ruby, Sapphire, Emerald, FireRed and LeafGreen save in two slots of 4 KB sectors; each
    // sector ends with its number, a signature and a save counter. The newest sector 0 starts
    // with the trainer name (7 letters, then $FF).
    private fun gen3(
        rom: ByteArray,
        save: ByteArray,
    ): String? {
        if (rom.size < 0xC0) return null
        Gen3Adapter.layoutFor(String(rom, 0xAC, 4, Charsets.US_ASCII), rom[0xBC].toInt() and 0xFF) ?: return null
        var newest: Int? = null
        var newestCounter = -1L
        for (sector in 0 until save.size / SECTOR_SIZE) {
            val base = sector * SECTOR_SIZE
            if (u16(save, base + SECTOR_ID) != 0 || u32(save, base + SECTOR_SIGNATURE) != SIGNATURE) continue
            val counter = u32(save, base + SECTOR_COUNTER)
            if (counter > newestCounter) {
                newestCounter = counter
                newest = base
            }
        }
        val start = newest ?: return null
        return gen3Text(save.copyOfRange(start, start + GEN3_NAME_LENGTH + 1))
    }

    /** A Ruby, Sapphire, Emerald, FireRed or LeafGreen name (ends at $FF, or after 7 letters), or null when it isn't one. */
    fun gen3Text(bytes: ByteArray): String? {
        val name = StringBuilder()
        for (byte in bytes.take(GEN3_NAME_LENGTH)) {
            when (val c = byte.toInt() and 0xFF) {
                0xFF -> break
                in 0xBB..0xD4 -> name.append('A' + (c - 0xBB))
                in 0xD5..0xEE -> name.append('a' + (c - 0xD5))
                in 0xA1..0xAA -> name.append('0' + (c - 0xA1))
                0x00 -> name.append(' ')
                0xAD -> name.append('.')
                0xAE -> name.append('-')
                else -> return null
            }
        }
        return name.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(
        bytes: ByteArray,
        at: Int,
    ): Long = u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)

    private const val GEN2_CHECK = 0x2008
    private const val GEN2_CHECK_VALUE = 99
    private const val GEN2_NAME = 0x200B
    const val GEN2_NAME_LENGTH = 11

    private const val SECTOR_SIZE = 0x1000
    private const val SECTOR_ID = 0xFF4
    private const val SECTOR_SIGNATURE = 0xFF8
    private const val SECTOR_COUNTER = 0xFFC
    private const val SIGNATURE = 0x08012025L
    const val GEN3_NAME_LENGTH = 7
}
