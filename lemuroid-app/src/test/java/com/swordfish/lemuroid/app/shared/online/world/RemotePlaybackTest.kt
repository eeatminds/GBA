package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RemotePlaybackTest {
    private val look = Look(1, 0, false)

    private fun at(
        x: Int,
        map: String = "24.4",
    ) = Avatar(map, x, 0, look)

    private class Message(val arrival: Long, val sentAt: Long, val x: Int)

    /**
     * A friend walks right one pixel a frame (60 a second) for [seconds]; their game is read every
     * [WorldSession.TICK_MS] and message number i takes [transit] (i) to arrive, in order (as on a
     * socket). Their clock is far from ours.
     */
    private fun walk(
        seconds: Int,
        transit: (Int) -> Long,
    ): List<Message> {
        val clockApart = 5_000_000L
        val messages = mutableListOf<Message>()
        var arrived = 0L
        var readAt = 1_000L
        var index = 0
        while (readAt < 1_000L + seconds * 1_000L) {
            arrived = maxOf(arrived, readAt + transit(index++))
            messages += Message(arrived, readAt + clockApart, ((readAt - 1_000L) * 60 / 1_000).toInt())
            readAt += WorldSession.TICK_MS
        }
        return messages
    }

    /** Plays [messages] back frame by frame (every 16 ms), each used only once it has arrived. */
    private fun playback(
        messages: List<Message>,
        from: Long,
        to: Long,
    ): Pair<List<Int>, WorldSession.Remote> {
        var remote: WorldSession.Remote? = null
        var next = 0
        val positions = mutableListOf<Int>()
        var now = 0L
        while (now <= to) {
            while (next < messages.size && messages[next].arrival <= now) {
                val message = messages[next++]
                remote = remote?.with(at(message.x), message.arrival, message.sentAt)
                    ?: WorldSession.Remote.of(at(message.x), message.arrival, message.sentAt)
            }
            if (now >= from) remote?.let { positions += it.at(now).x }
            now += 16
        }
        return positions to remote!!
    }

    private fun assertSmooth(positions: List<Int>) {
        val steps = positions.zipWithNext { a, b -> b - a }
        // Never backwards, never a jump, never stuck while they walk.
        assertTrue("backwards: $steps", steps.all { it >= 0 })
        assertTrue("jump: $steps", steps.all { it <= 2 })
        assertTrue("stuck: $positions", steps.windowed(2).none { window -> window.all { it == 0 } })
    }

    @Test
    fun `on a shaky mobile network the walk still looks like their own game`() {
        val random = Random(7)
        // Mostly 40 to 200 ms, and one message in ten 340 ms late (holding up the ones behind it).
        val delays = List(500) { 40L + if (random.nextInt(10) == 0) 300 else random.nextLong(0, 160) }
        val (positions, _) = playback(walk(14) { delays[it] }, from = 5_000, to = 14_000)
        assertSmooth(positions)
    }

    @Test
    fun `on a good network they are shown only a little behind`() {
        val random = Random(3)
        val delays = List(400) { 30L + random.nextLong(0, 15) }
        val (positions, remote) = playback(walk(10) { delays[it] }, from = 3_000, to = 10_000)
        assertSmooth(positions)
        assertTrue("delay ${remote.playbackDelay}", remote.playbackDelay <= 80)
    }

    @Test
    fun `after standing still, a new walk starts when it happened`() {
        var remote = WorldSession.Remote.of(at(0), now = 100, sentAt = 0)
        remote = remote.with(at(0), now = 1_100, sentAt = 1_000)
        remote = remote.with(at(2), now = 4_100, sentAt = 4_000)
        // Standing at 0 until just before the step, not sliding across the pause.
        assertEquals(0, remote.at(3_900 + 100 + remote.playbackDelay).x)
        assertEquals(2, remote.at(4_000 + 100 + remote.playbackDelay).x)
    }

    @Test
    fun `a message overtaken by a newer one is ignored`() {
        var remote = WorldSession.Remote.of(at(0), now = 50, sentAt = 0)
        remote = remote.with(at(4), now = 100, sentAt = 66)
        remote = remote.with(at(2), now = 110, sentAt = 33)
        assertEquals(4, remote.current.x)
    }

    @Test
    fun `a warp or a new map snaps instead of sliding`() {
        var remote = WorldSession.Remote.of(at(0), now = 0, sentAt = 0)
        remote = remote.with(at(4), now = 33, sentAt = 33)
        remote = remote.with(at(200), now = 66, sentAt = 66)
        assertEquals(4, remote.at(50 + remote.playbackDelay).x)
        remote = remote.with(at(0, map = "24.3"), now = 99, sentAt = 99)
        assertEquals("24.3", remote.at(10_000).map)
    }

    // Older app versions send no time: messages arrive bunched, playback stays steady.
    @Test
    fun `uneven messages without a send time still play back as a steady walk`() {
        val arrivals = listOf(0L, 66, 132, 240, 245, 330, 396, 520, 528, 594, 660)
        var remote = WorldSession.Remote.of(at(0), arrivals[0])
        arrivals.drop(1).forEachIndexed { index, time -> remote = remote.with(at((index + 1) * 4), time) }

        val positions = (150L..700L step 16).map { now -> remote.at(now).x }
        val steps = positions.zipWithNext { a, b -> b - a }
        assertTrue("steps $steps", steps.all { it >= 0 })
        assertTrue("steps $steps", steps.all { it <= 2 })
        assertTrue("positions $positions", steps.windowed(3).none { window -> window.all { it == 0 } })
        assertEquals(40, remote.at(10_000).x)
    }
}
