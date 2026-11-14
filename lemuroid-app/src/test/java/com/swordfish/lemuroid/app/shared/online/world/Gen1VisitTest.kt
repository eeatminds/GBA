package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * Joining the host's world in Red, Blue and Yellow. With a ROM (GEN1_ROM), checks the towns
 * buildings lead out to and writes the warp routines for the two-emulator test to GEN1_VISIT_OUT.
 */
class Gen1VisitTest {
    private fun adapter(): Gen1Adapter? {
        val file = System.getenv("GEN1_ROM")?.let(::File)?.takeIf { it.exists() } ?: return null
        val rom = file.readBytes()
        val checksum = ((rom[0x14E].toInt() and 0xFF) shl 8) or (rom[0x14F].toInt() and 0xFF)
        val layout = Gen1Adapter.layoutFor(String(rom, 0x134, 16, Charsets.US_ASCII), checksum) ?: return null
        return Gen1Adapter(Rom(rom, 0L), layout)
    }

    private fun gameboy(address: Long) = (address - GameMemory.SYSTEM_RAM_BASE + 0xC000).toInt()

    @Test
    fun `buildings lead out to the town or route they are in`() {
        val adapter = adapter()
        assumeTrue(adapter != null)
        adapter!!
        val expected =
            mapOf(
                0x29 to 0x01, // Viridian Pokémon Center: Viridian City
                0x2A to 0x01, // Viridian Mart
                0x26 to 0x00, // Red's house 2F: Pallet Town
                0x7C to 0x06, // Celadon Mart 3F: Celadon City
                0x3C to 0x0F, // Mt. Moon B1F: Route 4
                0x52 to 0x15, // Rock Tunnel 1F: Route 10
                0x5F to 0x05, // S.S. Anne 1F: Vermilion City
            )
        for ((map, town) in expected) assertEquals("map %02X".format(map), town, adapter.outsideMap(map))
        assertNull(adapter.outsideMap(0x01))

        // The routines for the two-emulator test: to a town, and into a building.
        val out = System.getenv("GEN1_VISIT_OUT")?.let(::File)
        val targets = listOf("1.0" to (5 to 6), "1.56" to (3 to 6), "1.12" to (9 to 20))
        val lines =
            targets.map { (map, step) ->
                val patch = adapter.visitWarp(map, step.first, step.second)!!
                // The routine first, the map's script pointed at it last.
                assertEquals(Gen1Adapter.VISIT_ROUTINE, gameboy(patch.writes.first().first))
                map + " " + patch.writes.joinToString(" ") { (address, bytes) -> "%04X:%s".format(gameboy(address), bytes.toHex()) }
            }
        out?.writeText(lines.joinToString("\n") + "\n")
        // Off the map: no warp.
        assertNull(adapter.visitWarp("1.0", 40, 6))
        assertNull(adapter.visitWarp("2.0", 5, 6))
    }

    // The routines that took a Red and a Yellow player into Pewter Mart (from Pallet Town), out of
    // its door into Pewter City, and saved with the map's own script.
    @Test
    fun `the warp and save routines as proven with real games`() {
        val adapter = adapter()
        assumeTrue(adapter != null)
        adapter!!
        val yellow = adapter.world == "kanto-gen1" && System.getenv("GEN1_NAME") == "yellow"
        val warp = adapter.visitWarp("1.56", 3, 6)!!.writes.first().second.toHex()
        assertEquals(
            if (yellow) {
                "3e38e08b3effea2ed43e02ea64d3ea5dd3215ed33e12223ec7223e06223e03223e00223e0177212cd7cbdec9"
            } else {
                "3e38e08b3effea2fd43e02ea65d3ea5ed3215fd33e12223ec7223e06223e03223e00223e0177212dd7cbdec9"
            },
            warp,
        )
    }

    @Test
    fun `the save puts the map's own script back first, also over one of our routines left in a save`() {
        val wram = ByteBuffer.allocate(0x2000)
        val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, wram)))
        fun put(address: Int, vararg bytes: Int) = memory.write(GameMemory.SYSTEM_RAM_BASE + (address - 0xC000), ByteArray(bytes.size) { bytes[it].toByte() })
        val red = Gen1Adapter(Rom(ByteArray(0x100000), 0L), Gen1Adapter.layoutFor("POKEMON RED", 0x91E6)!!)
        put(0xD36E, 0xAF, 0x4A)
        assertEquals("216ed336af23364a061c214878cdd635c9", red.saveGame(memory)!!.writes.first().second.toHex())
        assertTrue(red.canSave)
        // With a ROM: the in-place routine's address (from a save made by Update 36) is replaced by
        // the map's script from its header (Viridian's Pokémon Center: $425D).
        val adapter = adapter() ?: return
        if (System.getenv("GEN1_NAME") == "yellow") return
        put(0xD35E, 0x29)
        put(0xD36E, 0x5F, 0xCC)
        assertEquals("216ed3365d233642", adapter.saveGame(memory)!!.writes.first().second.toHex().take(16))
    }

    @Test
    fun `party, Pokédex and save on real work RAM`() {
        val adapter = adapter()
        val wram = System.getenv("GEN1_WRAM")?.let(::File)?.takeIf { it.exists() }
        assumeTrue(adapter != null && wram != null)
        adapter!!
        val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ByteBuffer.wrap(wram!!.readBytes()))))
        val party = adapter.party(memory)!!
        assertTrue(party.isNotEmpty())
        // Written back in another order, read back the same.
        adapter.writeParty(party.reversed())!!.applyTo(memory)
        assertEquals(party.reversed().map { it.key }, adapter.party(memory)!!.map { it.key })
        adapter.writeParty(party)!!.applyTo(memory)
        assertEquals(party.map { it.data.toHex() }, adapter.party(memory)!!.map { it.data.toHex() })
        // Mew (the game's number $15) is Pokédex number 151: the last bit of the owned and seen flags.
        adapter.registerInPokedex(memory, listOf(0x15))!!.applyTo(memory)
        val owned = if (System.getenv("GEN1_NAME") == "yellow") 0xD2F6 else 0xD2F7
        assertEquals(0x40, (memory.u8(GameMemory.SYSTEM_RAM_BASE + owned + 18 - 0xC000)!! and 0x40))
        // The save puts the map's own script back first.
        val save = adapter.saveGame(memory)!!
        assertEquals(Gen1Adapter.VISIT_ROUTINE, gameboy(save.writes.first().first))
        assertEquals(0x21, save.writes.first().second[0].toInt())
    }
}
