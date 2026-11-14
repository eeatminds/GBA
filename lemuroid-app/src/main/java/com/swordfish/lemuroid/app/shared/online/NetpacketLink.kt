package com.swordfish.lemuroid.app.shared.online

import com.swordfish.libretrodroid.LibretroDroid
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

/**
 * A GBA link cable through gpSP's netpacket interface, inside a trade & battle room.
 *
 * The core numbers players by their place on the cable (slot 0 leads the link), while the
 * room numbers them by player id. This class starts and stops the core's link as our link
 * group changes, and translates packets between slots and room ids.
 *
 * A real cable never goes quiet, and the games count on that: a Pokémon trade or battle gives
 * up after 5 seconds without its partner ("Communication error... turn the power OFF and ON"),
 * and gpSP drops a partner it hasn't heard from in 4. Over the internet a phone can go quiet for
 * longer (changing networks, a weak signal). So, between the apps:
 *
 * - Every packet is numbered and kept until the other app confirms it, and sent again until it
 *   does: nothing is lost or doubled over a network gap or a reconnect, and it all comes in order.
 * - Each app tells the others whether its game is on the link (it sent link data in its last
 *   [IN_LINK_FRAMES] frames), and again every [STATE_EVERY_MS].
 * - While both games are on the link and a partner's data stops coming (for [HOLD_AFTER_MS]),
 *   our game is held: it doesn't run, so none of its timers run out, until the data comes again,
 *   the partner's game leaves the link, the partner leaves, or [MAX_HOLD_MS] goes by.
 */
class NetpacketLink(
    private val core: Core,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val resendMs: Long = RESEND_MS,
) {
    /** The parts of LibretroDroid's netpacket bridge this uses. */
    interface Core {
        fun start(slot: Int)

        fun stop()

        fun peerConnected(slot: Int)

        fun peerDisconnected(slot: Int)

        fun push(
            fromSlot: Int,
            data: ByteArray,
        )

        fun wakeTakers()

        /** [type][target slot, 2 bytes][data], or null after [timeoutMs]. */
        fun takeOutgoing(timeoutMs: Int): ByteArray?

        fun setHold(hold: Boolean)

        fun framesSinceSend(): Int
    }

    object LibretroCore : Core {
        override fun start(slot: Int) = LibretroDroid.netpacketStart(slot)

        override fun stop() = LibretroDroid.netpacketStop()

        override fun peerConnected(slot: Int) = LibretroDroid.netpacketPeerConnected(slot)

        override fun peerDisconnected(slot: Int) = LibretroDroid.netpacketPeerDisconnected(slot)

        override fun push(
            fromSlot: Int,
            data: ByteArray,
        ) = LibretroDroid.netpacketPushIncoming(fromSlot, data)

        override fun wakeTakers() = LibretroDroid.netpacketWakeTakers()

        override fun takeOutgoing(timeoutMs: Int): ByteArray? = LibretroDroid.netpacketTakeOutgoing(timeoutMs)

        override fun setHold(hold: Boolean) = LibretroDroid.netpacketSetHold(hold)

        override fun framesSinceSend(): Int = LibretroDroid.netpacketFramesSinceSend()
    }

    // One other player on our link: our numbered packets to them, and theirs to us.
    private class Peer {
        val stream = newStream()
        var nextSeq = 0
        val unacked = ArrayDeque<Pair<Int, ByteArray>>()
        var oldestSentAt = 0L

        var theirStream: Int? = null
        var expected = 0
        var ackDue = false

        // Whether their game is on the link (what their app last told us), and when their game's
        // link data last came.
        var inLink = false
        var lastDataAt = 0L
    }

    private var mySlot: Int? = null
    private var leaderId: Int? = null
    private var connectedSlots: Set<Int> = emptySet()

    @Volatile private var slotByPlayer: Map<Int, Int> = emptyMap()

    @Volatile private var playerBySlot: Map<Int, Int> = emptyMap()

    private val peers = HashMap<Int, Peer>()
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    // Our game: the state in its last Pokémon link packet, and what we last told the others.
    @Volatile private var ourLinkState = 0
    private var toldInLink: Boolean? = null
    private var stateSentAt = 0L
    private var ackSentAt = 0L
    private var tickedAt = 0L

    private var heldSince: Long? = null

    /** Our link group changed; null (or a group of one) when we are not linked. */
    @Synchronized
    fun groupChanged(
        myId: Int,
        group: List<OnlineSession.LinkMember>?,
    ) {
        val members = group?.takeIf { it.size >= 2 }
        val newSlot = members?.firstOrNull { it.id == myId }?.slot
        val newLeader = members?.firstOrNull { it.slot == 0 }?.id

        // A new place on the cable, or a new leader, means a new link session for the core.
        val restart = newSlot != mySlot || newLeader != leaderId
        if (restart && mySlot != null) {
            core.stop()
            connectedSlots = emptySet()
        }
        slotByPlayer = members?.associate { it.id to it.slot } ?: emptyMap()
        playerBySlot = members?.associate { it.slot to it.id } ?: emptyMap()
        // Players no longer on our link go; on a new session everyone's numbering starts over.
        if (restart) {
            peers.clear()
            ourLinkState = 0
            toldInLink = null
        }
        peers.keys.retainAll(slotByPlayer.keys - myId)
        (slotByPlayer.keys - myId).forEach { peers.getOrPut(it) { Peer() } }
        if (restart && newSlot != null) core.start(newSlot)
        mySlot = newSlot
        leaderId = newLeader
        updateHold()

        // The leader is told when others plug in or out.
        if (newSlot == 0 && members != null) {
            val others = members.filter { it.id != myId }.map { it.slot }.toSet()
            (others - connectedSlots).sorted().forEach { core.peerConnected(it) }
            (connectedSlots - others).sorted().forEach { core.peerDisconnected(it) }
            connectedSlots = others
        } else {
            connectedSlots = emptySet()
        }
    }

    @Synchronized
    fun stop() {
        if (mySlot != null) core.stop()
        mySlot = null
        leaderId = null
        connectedSlots = emptySet()
        slotByPlayer = emptyMap()
        playerBySlot = emptyMap()
        peers.clear()
        release("the link stopped")
        outgoing.clear()
        core.wakeTakers()
    }

    /** A packet from room player [fromId]; dropped unless they are on our link. */
    @Synchronized
    fun push(
        fromId: Int,
        data: ByteArray,
    ) {
        val slot = slotByPlayer[fromId] ?: return
        val peer = peers[fromId] ?: return
        if (data.size < 3 || data[0] != FRAME_MARK) return
        when (data[1]) {
            FRAME_STATE -> {
                val inLink = data[2] == 1.toByte()
                if (inLink != peer.inLink) {
                    LinkLog.add("gba link: $fromId's game is ${if (inLink) "on" else "off"} the link")
                    // Their data has a moment to come before our game waits for it.
                    if (inLink) peer.lastDataAt = clock()
                }
                peer.inLink = inLink
                updateHold()
            }

            FRAME_ACK -> {
                if (data.size < FRAME_HEADER || readInt(data, 2) != peer.stream) return
                val seq = readInt(data, 6)
                val before = peer.unacked.size
                while (peer.unacked.isNotEmpty() && peer.unacked.first().first <= seq) peer.unacked.removeFirst()
                if (peer.unacked.size != before) peer.oldestSentAt = clock()
            }

            FRAME_DATA -> {
                if (data.size < FRAME_HEADER) return
                val stream = readInt(data, 2)
                val seq = readInt(data, 6)
                if (stream != peer.theirStream) {
                    // Their numbering starts over (a new link session, or their app restarted).
                    if (seq != 0) return
                    peer.theirStream = stream
                    peer.expected = 0
                }
                if (seq == peer.expected) {
                    peer.expected++
                    peer.lastDataAt = clock()
                    core.push(slot, data.copyOfRange(FRAME_HEADER, data.size))
                    updateHold()
                }
                // Confirmed with the next tick (repeats too, so they stop sending them).
                peer.ackDue = true
            }
        }
    }

    /**
     * Next room packet to send: [0 = data][target id, 2 bytes][message], the shape the other link
     * transports produce (room ids, not slots). Returns null on timeout or when stopped.
     */
    fun takeOutgoing(timeoutMs: Int): ByteArray? {
        tickIfDue()
        outgoing.poll()?.let { return it }
        val message = core.takeOutgoing(minOf(timeoutMs, POLL_MS)) ?: return outgoing.poll()
        if (message.size < 3) return outgoing.poll()
        val slot = ((message[1].toInt() and 0xFF) shl 8) or (message[2].toInt() and 0xFF)
        if (message[0].toInt() != LibretroDroid.NETPACKET_OUTGOING_DATA) {
            // The core refused a player: the session takes them off our link.
            val target = playerBySlot[slot] ?: return outgoing.poll()
            return byteArrayOf(message[0], (target shr 8).toByte(), target.toByte())
        }
        sendFromGame(slot, message.copyOfRange(3, message.size))
        return outgoing.poll()
    }

    // Our game's packet: numbered, kept, and queued for each player it is for.
    @Synchronized
    private fun sendFromGame(
        slot: Int,
        data: ByteArray,
    ) {
        linkState(data)?.let { ourLinkState = it }
        val targets = if (slot == BROADCAST) peers.keys.toList() else listOfNotNull(playerBySlot[slot])
        for (target in targets) {
            val peer = peers[target] ?: continue
            val frame = frame(FRAME_DATA, peer.stream, peer.nextSeq) + data
            if (peer.unacked.isEmpty()) peer.oldestSentAt = clock()
            peer.unacked.addLast(peer.nextSeq to frame)
            peer.nextSeq++
            // A partner who never answers (gone for good) can't make us keep everything forever.
            while (peer.unacked.size > MAX_UNACKED) peer.unacked.removeFirst()
            outgoing.offer(roomPacket(target, frame))
        }
    }

    private fun tickIfDue() {
        if (clock() - tickedAt >= TICK_MS) tick()
    }

    /**
     * About every [TICK_MS]: confirmations, our game's link state for the others, packets not yet
     * confirmed sent again, and whether our game waits for a partner.
     */
    @Synchronized
    fun tick() {
        val now = clock()
        tickedAt = now
        if (mySlot == null) return
        if (now - ackSentAt >= ACK_EVERY_MS) {
            ackSentAt = now
            peers.forEach { (id, peer) ->
                if (peer.ackDue && peer.expected > 0) {
                    peer.ackDue = false
                    outgoing.offer(roomPacket(id, frame(FRAME_ACK, peer.theirStream ?: 0, peer.expected - 1)))
                }
            }
        }
        val inLink = ourGameInLink()
        if (inLink != toldInLink || now - stateSentAt >= STATE_EVERY_MS) {
            if (inLink != toldInLink) LinkLog.add("gba link: our game is ${if (inLink) "on" else "off"} the link")
            toldInLink = inLink
            stateSentAt = now
            peers.keys.forEach { outgoing.offer(roomPacket(it, byteArrayOf(FRAME_MARK, FRAME_STATE, if (inLink) 1 else 0))) }
        }
        resend(force = false)
        updateHold()
    }

    /** Our connection to the room is back: everything not yet confirmed goes again now. */
    @Synchronized
    fun resendNow() = resend(force = true)

    /** Whether our game waits for a partner whose data is late. */
    val holding: Boolean @Synchronized get() = heldSince != null

    // Call with the lock held.
    private fun resend(force: Boolean) {
        val now = clock()
        peers.forEach { (id, peer) ->
            if (peer.unacked.isEmpty() || (!force && now - peer.oldestSentAt < resendMs)) return@forEach
            peer.oldestSentAt = now
            peer.unacked.forEach { (_, frame) -> outgoing.offer(roomPacket(id, frame)) }
        }
    }

    // Our game is on the Pokémon link: its last link packet said so, and it sent one in its last
    // few frames (frames, not seconds: a held game doesn't run, and stays on the link).
    private fun ourGameInLink(): Boolean = ourLinkState != 0 && core.framesSinceSend() < IN_LINK_FRAMES

    // Call with the lock held.
    private fun updateHold() {
        val now = clock()
        val late =
            if (mySlot != null && ourGameInLink()) {
                peers.entries.firstOrNull { (_, peer) -> peer.inLink && now - peer.lastDataAt > HOLD_AFTER_MS }?.key
            } else {
                null
            }
        val since = heldSince
        when {
            since != null && now - since > MAX_HOLD_MS -> {
                // Waited as long as the room keeps a seat: let the game see the link is gone.
                peers.values.forEach { it.inLink = false }
                release("waited ${MAX_HOLD_MS / 1000}s for the partner: giving up")
            }
            late != null && since == null -> {
                heldSince = now
                LinkLog.add("gba link: $late's link data is late: our game waits")
                core.setHold(true)
            }
            late == null && since != null -> release("going on after ${(now - since) / 100 / 10.0}s")
        }
    }

    private fun release(why: String) {
        if (heldSince == null) return
        heldSince = null
        LinkLog.add("gba link: $why")
        core.setHold(false)
    }

    companion object {
        const val BROADCAST = 0xFFFF
        const val RESEND_MS = 1_500L
        const val TICK_MS = 50L
        private const val POLL_MS = 50
        private const val ACK_EVERY_MS = 100L
        const val STATE_EVERY_MS = 250L
        const val HOLD_AFTER_MS = 500L

        // As long as the room keeps a seat for someone who lost their connection.
        const val MAX_HOLD_MS = 170_000L
        const val IN_LINK_FRAMES = 30
        private const val MAX_UNACKED = 8192

        // Between apps every packet is framed: [FRAME_MARK, kind, stream (4 bytes), number (4 bytes)]
        // then the game's packet; confirmations carry the last number received in order; states
        // are [FRAME_MARK, FRAME_STATE, 1 = on the link / 0].
        private const val FRAME_MARK: Byte = 0xA8.toByte()
        private const val FRAME_DATA: Byte = 1
        private const val FRAME_ACK: Byte = 2
        private const val FRAME_STATE: Byte = 3
        private const val FRAME_HEADER = 10

        // gpSP's Pokémon link packets: "MPK1", then flags (the sender's link state in the low half).
        private const val MPK1 = 0x4d504b31

        private fun linkState(data: ByteArray): Int? = if (data.size == 24 && readInt(data, 0) == MPK1) readInt(data, 4) and 0xFFFF else null

        private fun newStream(): Int = Random.nextInt().let { if (it == 0) 1 else it }

        private fun frame(
            kind: Byte,
            stream: Int,
            seq: Int,
        ) = byteArrayOf(
            FRAME_MARK, kind,
            (stream shr 24).toByte(), (stream shr 16).toByte(), (stream shr 8).toByte(), stream.toByte(),
            (seq shr 24).toByte(), (seq shr 16).toByte(), (seq shr 8).toByte(), seq.toByte(),
        )

        private fun readInt(
            data: ByteArray,
            at: Int,
        ): Int =
            ((data[at].toInt() and 0xFF) shl 24) or ((data[at + 1].toInt() and 0xFF) shl 16) or
                ((data[at + 2].toInt() and 0xFF) shl 8) or (data[at + 3].toInt() and 0xFF)

        private fun roomPacket(
            target: Int,
            frame: ByteArray,
        ): ByteArray = byteArrayOf(LibretroDroid.NETPACKET_OUTGOING_DATA.toByte(), (target shr 8).toByte(), target.toByte()) + frame
    }
}
