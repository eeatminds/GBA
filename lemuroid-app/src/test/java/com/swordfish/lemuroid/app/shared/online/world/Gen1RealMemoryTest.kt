package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

// Red/Blue/Yellow with the player's own ROM and work RAM dumps (GEN1_ROM, GEN1_DUMPS, GEN1_NAME);
// never stored with the code. Prints what the adapter reads and writes the sprites as PPM files.
class Gen1RealMemoryTest {
    @Test
    fun `gen 1 world read, places, neighbours and sprite`() {
        val romFile = System.getenv("GEN1_ROM")?.let(::File)
        val folder = System.getenv("GEN1_DUMPS")?.let(::File)
        val name = System.getenv("GEN1_NAME") ?: "red"
        assumeTrue(romFile != null && romFile.exists() && folder != null)
        val adapter = WorldAdapters.forRom("gb", romFile!!.readBytes())!!
        println("GEN1CHECK world ${adapter.world}")
        for (tag in listOf("center", "pallet", "walking")) {
            val file = File(folder, "$name-$tag.wram")
            if (!file.exists()) continue
            val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ByteBuffer.wrap(file.readBytes()))))
            val avatar = adapter.read(memory)
            println("GEN1CHECK $tag avatar $avatar place ${avatar?.let { adapter.place(it.map) }} neighbours ${adapter.neighbors(memory)}")
            println("GEN1CHECK $tag names ${adapter.neighbors(memory).keys.associateWith { adapter.place(it) }} trainer ${adapter.trainerName(memory)}")
            val picture = avatar?.let { adapter.sprite(it.look) } ?: continue
            val out = StringBuilder("P3\n${picture.width} ${picture.height}\n255\n")
            for (p in picture.pixels) {
                val argb = if (p == 0) 0xFFFF00FF.toInt() else p
                out.append("${(argb shr 16) and 255} ${(argb shr 8) and 255} ${argb and 255}\n")
            }
            File(folder, "$name-$tag-sprite.ppm").writeText(out.toString())
        }
    }
}
