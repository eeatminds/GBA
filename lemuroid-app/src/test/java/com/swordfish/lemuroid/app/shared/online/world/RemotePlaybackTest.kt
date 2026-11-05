package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlaybackTest {
    private val look = Look(1, 0, false)

    private fun at(
        x: Int,
        map: String = "24.4",
    ) = Avatar(map, x, 0, look)

    // A friend walks right one pixel a frame; the game is read every 66 ms (4 pixels), but the
    // messages arrive bunched: late, then two at once.
    @Test
    fun `uneven messages still play back as a steady walk`() {
        val arrivals = listOf(0L, 66, 132, 240, 245, 330, 396, 520, 528, 594, 660)
        var remote = WorldSession.Remote.of(at(0), arrivals[0])
        arrivals.drop(1).forEachIndexed { index, time -> remote = remote.with(at((index + 1) * 4), time) }

        // Played back 150 ms behind, frame by frame (every 16 ms).
        val positions = (150L..700L step 16).map { now -> remote.at(now - WorldSession.PLAYBACK_DELAY_MS).x }
        val steps = positions.zipWithNext { a, b -> b - a }
        // Never backwards, never stuck for long, never a big jump.
        assertTrue("steps $steps", steps.all { it >= 0 })
        assertTrue("steps $steps", steps.all { it <= 2 })
        assertTrue("positions $positions", steps.windowed(3).none { window -> window.all { it == 0 } })
        assertEquals(40, remote.at(10_000).x)
    }

    @Test
    fun `a warp or a new map snaps instead of sliding`() {
        var remote = WorldSession.Remote.of(at(0), 0)
        remote = remote.with(at(4), 66)
        remote = remote.with(at(200), 132)
        assertEquals(4, remote.at(100).x)
        remote = remote.with(at(0, map = "24.3"), 198)
        assertEquals("24.3", remote.at(1_000).map)
    }

    @Test
    fun `after standing still, a new walk starts when it arrives`() {
        var remote = WorldSession.Remote.of(at(0), 0)
        remote = remote.with(at(4), 3_000)
        // Standing at 0 until the new message's time, then on.
        assertEquals(0, remote.at(2_900).x)
        assertEquals(4, remote.at(3_000).x)
    }
}
