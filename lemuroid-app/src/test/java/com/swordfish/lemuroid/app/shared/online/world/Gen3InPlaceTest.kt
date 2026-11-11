package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The in-place trade and battle for FireRed/LeafGreen, against a pretend game memory. The script
 * bytes are the ones that ran in two linked games (FireRed 1.1 and LeafGreen 1.1, gpSP's
 * Pokemon link, 200 ms each way): a trade and a battle where the players stood in Viridian City,
 * both back on their tiles afterwards.
 */
class Gen3InPlaceTest {
    private val ewram = ByteBuffer.allocate(0x40000).order(ByteOrder.LITTLE_ENDIAN)
    private val iwram = ByteBuffer.allocate(0x8000).order(ByteOrder.LITTLE_ENDIAN)
    private val memory = GameMemory(listOf(GameMemory.Region(0x02000000, ewram), GameMemory.Region(0x03000000, iwram)))
    private val layout = Gen3Adapter.FIRERED_11
    private val link = layout.inPlace!!
    private val adapter = Gen3Adapter(Rom(ByteArray(0x100), 0x08000000), layout)

    private fun buffer(address: Long) = if (address >= 0x03000000) iwram else ewram

    private fun offset(address: Long) = (address and 0xFFFFFF).toInt()

    private fun put32(
        address: Long,
        value: Long,
    ) = buffer(address).putInt(offset(address), value.toInt())

    private fun put16(
        address: Long,
        value: Int,
    ) = buffer(address).putShort(offset(address), value.toShort())

    private fun put8(
        address: Long,
        value: Int,
    ) = buffer(address).put(offset(address), value.toByte())

    private fun hex(
        address: Long,
        length: Int,
    ) = memory.read(address, length)!!.joinToString("") { "%02x".format(it) }

    private val tradeStart =
        "69258801260d806a01259801c7031604800300251d0027210d800100060537fc03023f0301ff1a001c003e0301ff1a001c002502002702251f002599016b02"
    private val battleStart =
        "69258801260d806a01259801c7031604800100251c0027210d800100060540fc0302250000252700254b013f0301ff1a001c003e0301ff1a001c00250200" +
            "2702251f002599016b02"

    // FireRed 1.1, free on tile (26, 28) of Viridian City (3.1).
    private fun onTheMap(cb1: Long = link.cb1Overworld) {
        put32(layout.main, cb1 or 1)
        put32(layout.main + 4, layout.cb2Overworld or 1)
        put8(layout.fieldControlsLocked, 0)
        put8(link.scriptStatus, 2)
        val saveBlock = 0x0202552cL
        put32(layout.saveBlock1, saveBlock)
        put16(saveBlock, 26)
        put16(saveBlock + 2, 28)
        put8(saveBlock + 4, 3)
        put8(saveBlock + 5, 1)
    }

    private fun scriptRuns(at: Long) {
        assertEquals(0, memory.u8(link.scriptStatus))
        assertEquals(1, memory.u8(layout.fieldControlsLocked))
        assertEquals(at, memory.u32(link.scriptContext + 8))
        assertEquals(1, memory.u8(link.scriptContext + 1))
        assertEquals(link.scriptCmdTable, memory.u32(link.scriptContext + 0x5C))
        assertEquals(link.scriptCmdTableEnd, memory.u32(link.scriptContext + 0x60))
    }

    // The script ran to its end, as the game does it.
    private fun scriptDone() {
        put8(link.scriptStatus, 2)
        put8(layout.fieldControlsLocked, 0)
    }

    private fun task(
        index: Int,
        func: Long,
    ): Long {
        val task = link.tasks + index * 40L
        put32(task, func or 1)
        put8(task + 4, 1)
        return task
    }

    @Test
    fun `the trade starts with the game's link-up, then a cable club warp onto this tile`() {
        onTheMap()
        val steps = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        assertFalse(steps.running(memory))
        assertNotNull(steps.step(memory, 100))
        assertEquals(tradeStart, hex(link.scriptBuffer, tradeStart.length / 2))
        scriptRuns(link.scriptBuffer)
        assertTrue(steps.running(memory))
    }

    @Test
    fun `the battle also heals the party and keeps it and the bag, as the Colosseum does`() {
        onTheMap()
        adapter.inPlaceSteps(Talk.BATTLE, 0)!!.step(memory, 100)
        assertEquals(battleStart, hex(link.scriptBuffer, battleStart.length / 2))
    }

    @Test
    fun `nothing starts while the player is in a menu or talking`() {
        onTheMap()
        put8(layout.fieldControlsLocked, 1)
        val steps = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        assertNull(steps.step(memory, 100))
        assertEquals(0, memory.u8(link.scriptBuffer))
        assertFalse(steps.running(memory))
        // Free again: it starts.
        put8(layout.fieldControlsLocked, 0)
        assertNotNull(steps.step(memory, 200))
        scriptRuns(link.scriptBuffer)
    }

    @Test
    fun `the leader's game is told everyone is ready, as on A`() {
        onTheMap()
        val steps = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        steps.step(memory, 100)
        val linkup = task(3, link.linkupTasks.first { it in link.leaderWaits })
        // Not linked yet: it waits.
        put32(link.linkStatus, 0x20)
        assertNull(steps.step(memory, 200))
        // Two players, we lead.
        put32(link.linkStatus, 0x28)
        ewram.put(offset(link.linkPlayers), 0x42)
        assertNotNull(steps.step(memory, 300))
        assertEquals(link.linkupConfirm or 1, memory.u32(linkup))
        assertEquals(2, memory.u8(link.savedLinkPlayerCount))
        assertEquals(1, memory.u8(link.shouldAdvanceLinkState))
        assertEquals(0x42, memory.u8(link.savedLinkPlayers))
    }

    @Test
    fun `the other game never asks its player`() {
        onTheMap()
        val steps = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        steps.step(memory, 100)
        val linkup = task(3, link.linkupTasks.first { it in link.leaderWaits })
        put32(link.linkStatus, 0x09)
        steps.step(memory, 300)
        assertEquals(link.linkupTasks.first { it in link.leaderWaits } or 1, memory.u32(linkup))
    }

    @Test
    fun `linked on the map, each takes a seat, the leader leaves after, the other follows`() {
        onTheMap()
        val leader = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        leader.step(memory, 100)
        // The link-up and the warp happened: the map in link mode, free again.
        onTheMap(cb1 = link.cb1Link)
        put32(link.linkStatus, 0x28)
        put8(link.localLinkPlayerId, 0)
        assertNotNull(leader.step(memory, 5_000))
        assertEquals("1605800000c7032521002702", hex(link.scriptBuffer, 12))
        scriptRuns(link.scriptBuffer)
        // The trade screens, then back on the map, still linked.
        put32(layout.main + 4, 0x0804d64dL)
        assertNull(leader.step(memory, 10_000))
        assertTrue(leader.running(memory))
        onTheMap(cb1 = link.cb1Link)
        assertNull(leader.step(memory, 60_000))
        assertNull(leader.step(memory, 60_200))
        assertNotNull(leader.step(memory, 60_600))
        assertEquals("6825050002", hex(link.scriptBuffer, 5))
        scriptRuns(link.scriptBuffer)
        // Back on the normal map: done.
        onTheMap()
        assertFalse(leader.running(memory))
    }

    @Test
    fun `the other game leaves quietly when the leader leaves`() {
        onTheMap()
        val follower = adapter.inPlaceSteps(Talk.BATTLE, 0)!!
        follower.step(memory, 100)
        onTheMap(cb1 = link.cb1Link)
        put32(link.linkStatus, 0x09)
        put8(link.localLinkPlayerId, 1)
        follower.step(memory, 5_000)
        assertEquals("1605800100c7032520002702", hex(link.scriptBuffer, 12))
        scriptDone()
        // After the battle, the leader hasn't left yet: we wait.
        assertNull(follower.step(memory, 60_000))
        assertNull(follower.step(memory, 61_000))
        // The leader left: our game starts its "Terminating link" script; we leave instead.
        put8(link.scriptStatus, 0)
        put8(layout.fieldControlsLocked, 1)
        put32(link.scriptContext + 8, link.terminateLinkScript + 2)
        assertNotNull(follower.step(memory, 61_500))
        assertEquals("6825050002", hex(link.scriptBuffer, 5))
        scriptRuns(link.scriptBuffer)
    }

    @Test
    fun `the other game leaves by itself if the leader never does`() {
        onTheMap()
        val follower = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        follower.step(memory, 100)
        onTheMap(cb1 = link.cb1Link)
        put32(link.linkStatus, 0x09)
        follower.step(memory, 5_000)
        scriptDone()
        assertNull(follower.step(memory, 60_000))
        assertNull(follower.step(memory, 65_000))
        assertNotNull(follower.step(memory, 66_100))
        assertEquals("6825050002", hex(link.scriptBuffer, 5))
    }

    @Test
    fun `nobody linked up, so the link-up is called off as on B`() {
        onTheMap()
        val steps = adapter.inPlaceSteps(Talk.TRADE, 0)!!
        steps.step(memory, 100)
        val linkup = task(5, link.linkupTasks.first())
        assertNull(steps.step(memory, 20_000))
        assertNotNull(steps.step(memory, 31_000))
        assertEquals(link.linkupFailed or 1, memory.u32(linkup))
    }

    @Test
    fun `our avatar isn't read while the game shows both players itself`() {
        onTheMap(cb1 = link.cb1Link)
        assertNull(adapter.read(memory))
        assertTrue(adapter.scriptPending(memory))
    }
}
