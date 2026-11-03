package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class TrainerNamesTest {
    private val crystalHeader =
        ByteArray(0x8000).also {
            "PM_CRYSTAL".toByteArray().copyInto(it, 0x134)
            "BYTE".toByteArray().copyInto(it, 0x13F)
        }

    @Test
    fun `Gen 2 name from the save, only when the save is good`() {
        val save = ByteArray(0x8000)
        save[0x2008] = 99
        // "Kris": K, r, i, s, end.
        byteArrayOf(0x8A.toByte(), 0xB1.toByte(), 0xA8.toByte(), 0xB2.toByte(), 0x50).copyInto(save, 0x200B)
        assertEquals("Kris", TrainerNames.fromSave("gbc", crystalHeader, save))
        save[0x2008] = 0
        assertNull(TrainerNames.fromSave("gbc", crystalHeader, save))
        // Other games: no name.
        assertNull(TrainerNames.fromSave("gbc", ByteArray(0x8000), save))
        assertNull(TrainerNames.fromSave("nes", crystalHeader, save))
    }

    // Saves made in a desktop emulator (the test intro named both players with A's).
    @Test
    fun `names from real Gold and FireRed saves`() {
        val folder = System.getenv("WORLD_E2E")?.let(::File)
        var checked = 0
        val gold = System.getenv("GOLD_ROM")?.let(::File)
        if (folder != null && gold != null && gold.exists() && File(folder, "gold/gold.srm").exists()) {
            assertEquals("AAAAAA", TrainerNames.fromSave("gbc", readRom(gold), File(folder, "gold/gold.srm").readBytes()))
            checked++
        }
        val firered = System.getenv("FIRERED_ROM")?.let(::File)
        if (folder != null && firered != null && firered.exists() && File(folder, "firered.sav").exists()) {
            assertEquals("AAAAAAA", TrainerNames.fromSave("gba", firered.readBytes(), File(folder, "firered.sav").readBytes()))
            // A blank save has no name.
            assertNull(TrainerNames.fromSave("gba", firered.readBytes(), ByteArray(0x20000) { -1 }))
            checked++
        }
        assumeTrue(checked > 0)
    }

    private fun readRom(file: File): ByteArray {
        if (!file.name.endsWith(".zip", ignoreCase = true)) return file.readBytes()
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".gbc", ignoreCase = true) }
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }
}
