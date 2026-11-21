package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldAdaptersTest {
    // A Crystal-looking header: title and game code are all the reader checks.
    private val rom =
        ByteArray(0x8000).also {
            "PM_CRYSTAL".toByteArray().copyInto(it, 0x134)
            "BYTE".toByteArray().copyInto(it, 0x13F)
        }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a plain ROM is read as it is`() {
        assertArrayEquals(rom, WorldAdapters.readRom(ByteArrayInputStream(rom), "crystal.gbc"))
    }

    // Games added as a zip keep the name of the game inside it, not ".zip".
    @Test
    fun `a zipped game is unzipped even when its name is the file inside`() {
        val zipped = zip("readme.txt" to ByteArray(10), "Pokemon Crystal.gbc" to rom)
        val read = WorldAdapters.readRom(ByteArrayInputStream(zipped), "Pokemon Crystal.gbc")
        assertArrayEquals(rom, read)
        assertNotNull(WorldAdapters.forRom("gbc", read!!))
        // Another name: the first ROM in the zip.
        assertArrayEquals(rom, WorldAdapters.readRom(ByteArrayInputStream(zipped), "game.zip"))
    }
}
