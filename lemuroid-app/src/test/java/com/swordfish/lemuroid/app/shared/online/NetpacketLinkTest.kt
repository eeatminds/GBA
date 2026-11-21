package com.swordfish.lemuroid.app.shared.online

import com.swordfish.lemuroid.app.shared.online.OnlineSession.LinkMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class NetpacketLinkTest {
    private class FakeCore : NetpacketLink.Core {
        val calls = mutableListOf<String>()
        val received = mutableListOf<Pair<Int, ByteArray>>()
        val toSend = ArrayDeque<ByteArray>()
        var held = false
        var framesSinceSend = 0

        override fun start(slot: Int) {
            calls += "start $slot"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun peerConnected(slot: Int) {
            calls += "connected $slot"
        }

        override fun peerDisconnected(slot: Int) {
            calls += "disconnected $slot"
        }

        override fun push(
            fromSlot: Int,
            data: ByteArray,
        ) {
            calls += "data from $fromSlot"
            received += fromSlot to data
        }

        override fun wakeTakers() {
            calls += "wake"
        }

        override fun takeOutgoing(timeoutMs: Int): ByteArray? = toSend.pollFirst()

        override fun setHold(hold: Boolean) {
            held = hold
        }

        override fun framesSinceSend(): Int = framesSinceSend

        // The game sends [data] to [slot] (or everyone).
        fun send(
            data: ByteArray,
            slot: Int = NetpacketLink.BROADCAST,
        ) {
            toSend.addLast(byteArrayOf(0, (slot shr 8).toByte(), slot.toByte()) + data)
            framesSinceSend = 0
        }
    }

    private var now = 1_000L
    private val core = FakeCore()
    private val link = NetpacketLink(core, clock = { now })

    private fun group(vararg members: Pair<Int, Int>) = members.map { (id, slot) -> LinkMember(id, slot) }

    // Everything [from] sends now: (room target, payload).
    private fun drain(from: NetpacketLink): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        while (true) {
            val packet = from.takeOutgoing(0) ?: break
            out += (((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)) to packet.copyOfRange(3, packet.size)
        }
        return out
    }

    // gpSP's Pokémon link packet with link state [state].
    private fun mpk(
        state: Int,
        tag: Int = 0,
    ) = byteArrayOf(0x4d, 0x50, 0x4b, 0x31, 0, 0, 0, state.toByte(), tag.toByte()) + ByteArray(15)

    // Two apps, players 4 (leading) and 7, linked.
    private inner class Apps {
        val coreA = FakeCore()
        val a = NetpacketLink(coreA, clock = { now })
        val coreB = FakeCore()
        val b = NetpacketLink(coreB, clock = { now })

        init {
            a.groupChanged(4, group(4 to 0, 7 to 1))
            b.groupChanged(7, group(4 to 0, 7 to 1))
        }

        // Moves everything both apps send, except what [lose] says the network loses.
        fun exchange(lose: (from: Int, payload: ByteArray) -> Boolean = { _, _ -> false }) {
            repeat(4) {
                drain(a).forEach { (to, payload) -> if (to == 7 && !lose(4, payload)) b.push(4, payload) }
                drain(b).forEach { (to, payload) -> if (to == 4 && !lose(7, payload)) a.push(7, payload) }
            }
        }

        fun tick(ms: Long) {
            now += ms
            a.tick()
            b.tick()
        }

        // Both games on the Pokémon link, and both apps know it.
        fun bothOnLink() {
            coreA.send(mpk(2))
            coreB.send(mpk(2))
            exchange()
            tick(NetpacketLink.STATE_EVERY_MS)
            exchange()
        }
    }

    @Test
    fun `no link until we are in a group of two or more`() {
        link.groupChanged(myId = 3, group = null)
        link.groupChanged(myId = 3, group = group(3 to 0))
        assertEquals(emptyList<String>(), core.calls)
        link.push(5, byteArrayOf(1))
        assertEquals(emptyList<String>(), core.calls)
    }

    @Test
    fun `the leader starts in slot 0 and hears about each member`() {
        link.groupChanged(myId = 4, group = group(4 to 0, 7 to 1))
        assertEquals(listOf("start 0", "connected 1"), core.calls)

        // A third player plugs in: no restart, just one more member.
        core.calls.clear()
        link.groupChanged(myId = 4, group = group(4 to 0, 7 to 1, 2 to 2))
        assertEquals(listOf("connected 2"), core.calls)

        // Player 7 unplugs.
        core.calls.clear()
        link.groupChanged(myId = 4, group = group(4 to 0, 2 to 2))
        assertEquals(listOf("disconnected 1"), core.calls)
    }

    @Test
    fun `a member starts in its own slot and restarts when the leader changes`() {
        link.groupChanged(myId = 7, group = group(4 to 0, 7 to 1))
        assertEquals(listOf("start 1"), core.calls)

        // The leader left; the server gave everyone new slots.
        core.calls.clear()
        link.groupChanged(myId = 7, group = group(7 to 0, 2 to 1))
        assertEquals(listOf("stop", "start 0", "connected 1"), core.calls)

        // Unplugged entirely.
        core.calls.clear()
        link.groupChanged(myId = 7, group = null)
        assertEquals(listOf("stop"), core.calls)
    }

    @Test
    fun `stop ends the link and wakes the sender`() {
        link.groupChanged(myId = 1, group = group(0 to 0, 1 to 1))
        core.calls.clear()
        link.stop()
        assertEquals(listOf("stop", "wake"), core.calls)
        core.calls.clear()
        link.stop()
        assertEquals(listOf("wake"), core.calls)
    }

    @Test
    fun `packets arrive once and in order, with what the network lost sent again`() {
        val apps = Apps()
        repeat(10) { apps.coreA.send(byteArrayOf(it.toByte())) }
        // The network loses the 3rd and 4th packets the first time.
        var lost = 0
        apps.exchange { from, payload -> from == 4 && payload.size == 11 && payload[10].toInt() in 2..3 && lost++ < 2 }
        assertEquals(listOf<Byte>(0, 1), apps.coreB.received.map { it.second[0] })
        // Confirmations, then the resend of what wasn't confirmed.
        apps.tick(200)
        apps.exchange()
        apps.tick(NetpacketLink.RESEND_MS)
        apps.exchange()
        assertEquals((0..9).map { it.toByte() }, apps.coreB.received.map { it.second[0] })
        assertTrue(apps.coreB.received.all { it.first == 0 })
        // Sent again after that: nothing doubles.
        apps.tick(NetpacketLink.RESEND_MS)
        apps.exchange()
        apps.a.resendNow()
        apps.b.resendNow()
        apps.exchange()
        assertEquals(10, apps.coreB.received.size)
    }

    @Test
    fun `a gap in the partner's data holds our game until it comes again`() {
        val apps = Apps()
        apps.bothOnLink()
        apps.tick(100)
        assertFalse(apps.coreA.held)
        // The network goes quiet: B's game keeps sending, nothing arrives.
        apps.coreB.send(mpk(2, tag = 1))
        drain(apps.b)
        apps.tick(NetpacketLink.HOLD_AFTER_MS + 50)
        assertTrue(apps.coreA.held)
        assertTrue(apps.a.holding)
        // Held, A's game sends nothing, but stays on the link (it doesn't run).
        apps.tick(10_000)
        assertTrue(apps.coreA.held)
        // The network is back: B's packets (sent again) arrive, A goes on.
        apps.b.resendNow()
        apps.exchange()
        assertFalse(apps.coreA.held)
        assertEquals(1, apps.coreA.received.last().second[8].toInt())
    }

    @Test
    fun `no hold when the partner's game left the link or ours isn't on it`() {
        val apps = Apps()
        apps.bothOnLink()
        // B's game leaves the link: it stops sending; its app says so.
        apps.coreB.framesSinceSend = NetpacketLink.IN_LINK_FRAMES
        apps.tick(NetpacketLink.STATE_EVERY_MS)
        apps.exchange()
        apps.tick(NetpacketLink.HOLD_AFTER_MS * 4)
        assertFalse(apps.coreA.held)

        // Our game not on the link (no Pokémon link packets from it): never held.
        val other = Apps()
        other.coreB.send(mpk(2))
        other.exchange()
        other.tick(NetpacketLink.STATE_EVERY_MS)
        other.exchange()
        other.tick(NetpacketLink.HOLD_AFTER_MS * 4)
        assertFalse(other.coreA.held)
    }

    @Test
    fun `a partner who never comes back is given up on`() {
        val apps = Apps()
        apps.bothOnLink()
        apps.tick(NetpacketLink.HOLD_AFTER_MS + 50)
        assertTrue(apps.coreA.held)
        apps.tick(NetpacketLink.MAX_HOLD_MS + 50)
        assertFalse(apps.coreA.held)
        apps.tick(1_000)
        assertFalse(apps.coreA.held)
    }

    @Test
    fun `leaving the group lets our game go`() {
        val apps = Apps()
        apps.bothOnLink()
        apps.tick(NetpacketLink.HOLD_AFTER_MS + 50)
        assertTrue(apps.coreA.held)
        apps.a.groupChanged(4, null)
        assertFalse(apps.coreA.held)
    }
}
