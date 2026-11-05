package com.swordfish.lemuroid.app.shared.online

import com.swordfish.lemuroid.app.shared.online.OnlineSession.LinkMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetpacketLinkTest {
    private class FakeCore : NetpacketLink.Core {
        val calls = mutableListOf<String>()

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
        }

        override fun wakeTakers() {
            calls += "wake"
        }
    }

    private val core = FakeCore()
    private val link = NetpacketLink(core)

    private fun group(vararg members: Pair<Int, Int>) = members.map { (id, slot) -> LinkMember(id, slot) }

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

        // Packets are translated between room ids and cable slots.
        assertEquals(2, link.roomTarget(2))
        assertEquals(NetpacketLink.BROADCAST, link.roomTarget(NetpacketLink.BROADCAST))
        assertNull(link.roomTarget(3))
        core.calls.clear()
        link.push(7, byteArrayOf(1))
        link.push(9, byteArrayOf(1))
        assertEquals(listOf("data from 1"), core.calls)

        // Player 7 unplugs.
        core.calls.clear()
        link.groupChanged(myId = 4, group = group(4 to 0, 2 to 2))
        assertEquals(listOf("disconnected 1"), core.calls)
    }

    @Test
    fun `a member starts in its own slot and restarts when the leader changes`() {
        link.groupChanged(myId = 7, group = group(4 to 0, 7 to 1))
        assertEquals(listOf("start 1"), core.calls)
        assertEquals(4, link.roomTarget(0))

        // The leader left; the server gave everyone new slots.
        core.calls.clear()
        link.groupChanged(myId = 7, group = group(7 to 0, 2 to 1))
        assertEquals(listOf("stop", "start 0", "connected 1"), core.calls)

        // Unplugged entirely.
        core.calls.clear()
        link.groupChanged(myId = 7, group = null)
        assertEquals(listOf("stop"), core.calls)
        assertNull(link.roomTarget(1))
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
}
