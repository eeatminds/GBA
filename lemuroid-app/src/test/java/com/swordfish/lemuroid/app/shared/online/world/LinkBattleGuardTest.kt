package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class LinkBattleGuardTest {
    private val ewram = ByteBuffer.allocate(0x40000)
    private val iwram = ByteBuffer.allocate(0x8000)
    private val memory =
        GameMemory(listOf(GameMemory.Region(0x02000000L, ewram), GameMemory.Region(0x03000000L, iwram)))
    private val guard = LinkBattleGuard.forGame("BPRE", 0)!!

    private fun put32(
        address: Long,
        value: Long,
    ) {
        memory.write(address, ByteArray(4) { ((value shr (8 * it)) and 0xFF).toByte() })
    }

    private fun state(
        cb2: Long,
        linked: Boolean,
        flags: Long,
    ) {
        put32(0x030030f4, cb2)
        memory.write(0x03003f64, byteArrayOf(if (linked) 1 else 0))
        put32(0x02022b4c, flags)
    }

    private val battle = 0x08011100L or 1

    @Test
    fun `flags cleared while the link battle ends are put back`() {
        state(battle, linked = true, flags = 0x2e)
        assertFalse(guard.check(memory))
        // The hack's end-of-battle cleanup clears them; the link is still up.
        state(battle, linked = true, flags = 0)
        assertTrue(guard.check(memory))
        assertEquals(0x2eL, memory.u32(0x02022b4c))
        assertFalse(guard.check(memory))
    }

    @Test
    fun `nothing changes once the link is closed, outside battles or in battles without a link`() {
        state(battle, linked = true, flags = 0x2e)
        guard.check(memory)
        // The game closed the link itself (the normal end): the flags are its own business now.
        state(battle, linked = false, flags = 0)
        assertFalse(guard.check(memory))
        assertEquals(0L, memory.u32(0x02022b4c))
        // Back on the map, and in a later wild battle (no link bit ever seen).
        state(0x080565b4L or 1, linked = true, flags = 0)
        assertFalse(guard.check(memory))
        state(battle, linked = true, flags = 0)
        assertFalse(guard.check(memory))
        assertEquals(0L, memory.u32(0x02022b4c))
    }

    @Test
    fun `only FireRed and LeafGreen based games`() {
        assertEquals(true, LinkBattleGuard.forGame("BPGE", 1) != null)
        assertNull(LinkBattleGuard.forGame("BPEE", 0))
        assertNull(LinkBattleGuard.forGame("AXVE", 1))
        assertNull(LinkBattleGuard.forGame("BPRE", 2))
    }
}
