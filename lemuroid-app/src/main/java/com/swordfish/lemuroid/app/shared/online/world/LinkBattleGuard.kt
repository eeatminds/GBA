package com.swordfish.lemuroid.app.shared.online.world

/**
 * Keeps a FireRed/LeafGreen link battle a link battle until it has ended.
 *
 * Some ROM hacks built on FireRed clear the battle type flags in their own end-of-battle cleanup
 * (Radical Red does, as the last thing of it). On the link leader's game that cleanup runs while
 * the link battle is still ending, so the game takes the single-player way out: no closing of the
 * link and no end screen, straight to saving and back to the map, about five seconds before the
 * other game, whose link then times out ("Communication error") on both. The original games never
 * clear the flags there, so nothing changes for them.
 *
 * Checked between frames while a link room runs: in a battle, with the link up, the flags we saw
 * with the link bit are put back if they lose it.
 */
class LinkBattleGuard(
    private val battleMainCb2: Long,
) {
    private var linkFlags = 0L

    /** One look at the running game. Returns true when it put the flags back. */
    fun check(memory: GameMemory): Boolean {
        val cb2 = memory.u32(MAIN + 4) ?: return false
        val linked = memory.u8(RECEIVED_REMOTE_LINK_PLAYERS) ?: return false
        if ((cb2 and 1L.inv()) != battleMainCb2 || linked == 0) {
            linkFlags = 0
            return false
        }
        val flags = memory.u32(BATTLE_TYPE_FLAGS) ?: return false
        if (flags and BATTLE_TYPE_LINK != 0L) {
            linkFlags = flags
            return false
        }
        if (linkFlags == 0L) return false
        val bytes = ByteArray(4) { ((linkFlags shr (8 * it)) and 0xFF).toByte() }
        return memory.write(BATTLE_TYPE_FLAGS, bytes)
    }

    companion object {
        private const val MAIN = 0x030030f0L
        private const val BATTLE_TYPE_FLAGS = 0x02022b4cL
        private const val RECEIVED_REMOTE_LINK_PLAYERS = 0x03003f64L
        private const val BATTLE_TYPE_LINK = 0x2L

        /** For a GBA game by its header ([gameCode], [version]): FireRed and LeafGreen 1.0 and 1.1, and hacks of them. */
        fun forGame(
            gameCode: String,
            version: Int,
        ): LinkBattleGuard? {
            if (gameCode != "BPRE" && gameCode != "BPGE") return null
            return when (version) {
                0 -> LinkBattleGuard(0x08011100L)
                1 -> LinkBattleGuard(0x08011114L)
                else -> null
            }
        }

        fun forRom(rom: ByteArray): LinkBattleGuard? {
            if (rom.size < 0xC0) return null
            return forGame(String(rom, 0xAC, 4, Charsets.US_ASCII), rom[0xBC].toInt() and 0xFF)
        }
    }
}
