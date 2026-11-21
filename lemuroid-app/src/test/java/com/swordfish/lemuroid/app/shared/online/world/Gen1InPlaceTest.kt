package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class Gen1InPlaceTest {
    private fun adapter(title: String, checksum: Int): Gen1Adapter {
        val rom = ByteArray(0x100000)
        return Gen1Adapter(Rom(rom, 0L), Gen1Adapter.layoutFor(title, checksum)!!)
    }

    // The bytes the two-emulator test traded and battled with (map script $425D: the Pokemon Center);
    // both games' saves afterwards held the map's own script.
    @Test
    fun `the in-place routine is the one proven with two linked games`() {
        val (redA, redB) = adapter("POKEMON RED", 0x91E6).inPlaceRoutine(Talk.TRADE, 0x425D)
        assertEquals(
            "003e01ea2bd1216ed3365d233642cded22cdaf20cded220e32cd3937061c214878cdd6352147cc3e0322af77e0a9ea42cccd7f222147cc2aa63cca77d6af3277c36ad6",
            redA.toHex(),
        )
        assertEquals("3e02ea2bd10601211753cdd635afea2bd1216ed3365d233642060121d772cdd635c30723", redB.toHex())
        val (yellowA, yellowB) = adapter("POKEMON YELLOW", 0x047C).inPlaceRoutine(Talk.BATTLE, 0x425D)
        assertEquals(
            "003e01ea2ad1216dd3365d233642cd4921cd641ecd49210e32cd2f37061c21917bcd843e2147cc3e0322af77e0a9ea42cccddb202147cc2aa63cca76d6af3277c369d6",
            yellowA.toHex(),
        )
        assertEquals("3e03ea2ad1060121a553cd843eafea2ad1216dd3365d233642060121ac71cd843ec36b21", yellowB.toHex())
    }

    @Test
    fun `starts only when the player is free, then runs until the map script and link state are back`() {
        val wram = ByteBuffer.allocate(0x2000)
        val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, wram)))
        fun put(address: Int, vararg bytes: Int) = memory.write(GameMemory.SYSTEM_RAM_BASE + (address - 0xC000), ByteArray(bytes.size) { bytes[it].toByte() })
        val red = adapter("POKEMON RED", 0x91E6)
        put(0xD36E, 0x5D, 0x42)
        put(0xCFC4, 1) // a text box is open
        val steps = red.inPlaceSteps(Talk.TRADE, 0)
        assertEquals(null, steps.step(memory, 0))
        assertFalse(steps.running(memory))
        put(0xCFC4, 0)
        assertTrue(steps.step(memory, 0)!!.contains("425D"))
        assertEquals(Gen1Adapter.PART_A, memory.u16(GameMemory.SYSTEM_RAM_BASE + (0xD36E - 0xC000)))
        assertTrue(steps.running(memory))
        assertTrue(red.scriptPending(memory))
        // Our routine put the map's script back and marked the link as going (meeting the friend).
        put(0xD36E, 0x5D, 0x42)
        put(0xD12B, 1)
        assertTrue(steps.running(memory))
        assertTrue(!red.linkActive(memory))
        assertTrue(!red.scriptPending(memory))
        // The game reloaded the map (its own script is back) but the trade still goes.
        put(0xD36E, 0x5D, 0x42)
        put(0xD12B, 0x32)
        assertTrue(steps.running(memory))
        assertTrue(red.linkActive(memory))
        put(0xD12B, 0)
        assertFalse(steps.running(memory))
    }
}
