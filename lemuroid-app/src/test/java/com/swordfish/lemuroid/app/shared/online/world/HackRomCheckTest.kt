package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

// A look at a ROM hack's world with the player's own ROM and a memory dump (HACK_ROM, HACK_DUMP);
// never stored with the code. Writes the sprite drawn for a player as a PPM next to the dump.
class HackRomCheckTest {
    @Test
    fun `hack world read and sprite`() {
        val romFile = System.getenv("HACK_ROM")?.let(::File)
        val folder = System.getenv("HACK_DUMP")?.let(::File)
        assumeTrue(romFile != null && romFile.exists() && folder != null && File(folder, "game.ewram").exists())
        val adapter = WorldAdapters.forRom("gba", romFile!!.readBytes())!!
        val memory =
            GameMemory(
                listOf(
                    GameMemory.Region(0x02000000, ByteBuffer.wrap(File(folder, "game.ewram").readBytes())),
                    GameMemory.Region(0x03000000, ByteBuffer.wrap(File(folder, "game.iwram").readBytes())),
                ),
            )
        val avatar = adapter.read(memory)
        println("HACKCHECK avatar $avatar place ${avatar?.let { adapter.place(it.map) }}")
        val picture = avatar?.let { adapter.sprite(it.look) }
        println("HACKCHECK sprite ${picture?.width}x${picture?.height} opaque ${picture?.pixels?.count { it != 0 }}")
        if (picture != null) {
            val out = StringBuilder("P3\n${picture.width} ${picture.height}\n255\n")
            for (p in picture.pixels) out.append("${(p shr 16) and 255} ${(p shr 8) and 255} ${p and 255}\n")
            File(folder, "sprite.ppm").writeText(out.toString())
        }
    }
}
