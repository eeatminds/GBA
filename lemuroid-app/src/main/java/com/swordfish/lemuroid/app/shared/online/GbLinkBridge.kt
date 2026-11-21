package com.swordfish.lemuroid.app.shared.online

import timber.log.Timber
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A Game Boy link cable that runs through the room server.
 *
 * Gambatte's "network link" connects to a TCP address and swaps 2-byte messages
 * ([data, speed flag]): the side that drives the transfer writes 2 bytes and waits for 2
 * back; the other side answers as soon as it sees them. This bridge listens on this phone
 * only (127.0.0.1), Gambatte connects to it, and every message goes to and from the linked
 * player as a room packet.
 *
 * While nobody is linked, it answers like an unplugged cable (0xFF), so the game never
 * waits forever.
 *
 * Our Gambatte also sends whole blocks ([0, BLOCK_MARK, length low, length high, bytes...]) for
 * the big exchanges of Gold, Silver and Crystal, and for their "both ready?" checks: one packet
 * each instead of one round trip per byte. A block sent before we are linked is held for a few
 * seconds (the room links two players a moment after one accepts the other's invitation), then
 * forwarded; with still nobody to answer it, the game gets [0, NO_PARTNER_MARK] and gives up.
 *
 * Nothing is lost when a phone's connection drops for a while (a train, a lift, a weak signal):
 * between the two bridges every message is numbered and sent again until the other bridge
 * says it has it, and repeats are dropped. The games just wait meanwhile, as on a cable.
 */
class GbLinkBridge(
    private val ports: IntRange = DEFAULT_PORTS,
    private val holdBlocksMs: Long = HOLD_BLOCKS_MS,
    private val resendMs: Long = RESEND_MS,
) {
    private val lock = Any()
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var peer: Int? = null

    // Single byte transfers since the current link started: sent by our game, and answered
    // by the peer. If the link breaks while our game still waits for answers, we answer.
    private var sent = 0
    private var received = 0

    // Blocks our game sent while unlinked, with when (System.nanoTime ms).
    private val heldBlocks = ArrayList<Pair<Long, ByteArray>>()

    // Our numbered messages to the peer ([stream], from 0), kept until the peer has them.
    private class Unacked(val seq: Int, val frame: ByteArray, var sentAt: Long)

    private var stream = newStream()
    private var nextSeq = 0
    private val unacked = ArrayDeque<Unacked>()

    // The peer's numbered messages: their stream, and the next number we expect.
    private var peerStream: Int? = null
    private var expected = 0

    // For the link log: how many of each, so only the first few (and then every so often) show.
    private var ignored = 0
    private var unplugged = 0
    private var resends = 0

    /** Starts listening and returns the port Gambatte should connect to, or null. */
    fun start(): Int? {
        synchronized(lock) {
            if (running) return server?.localPort
            for (port in ports) {
                val candidate =
                    runCatching { ServerSocket(port, 1, GAME_ADDRESS) }.getOrNull() ?: continue
                server = candidate
                running = true
                Thread({ acceptLoop(candidate) }, "gb-link-accept").apply {
                    isDaemon = true
                    start()
                }
                Thread({ timerLoop() }, "gb-link-timer").apply {
                    isDaemon = true
                    start()
                }
                return port
            }
        }
        Timber.e("No free port for the Game Boy link")
        return null
    }

    fun stop() {
        synchronized(lock) {
            running = false
            runCatching { server?.close() }
            runCatching { socket?.close() }
            server = null
            socket = null
            peer = null
            heldBlocks.clear()
            unacked.clear()
        }
        outgoing.clear()
        // Wake the room's sending thread.
        outgoing.offer(ByteArray(0))
    }

    /** The room player our cable is plugged into, or null when unplugged. */
    fun setPeer(id: Int?) {
        synchronized(lock) {
            if (id == peer) return
            val old = peer
            LinkLog.add("bridge: plugged into ${id ?: "nobody"} (was ${old ?: "nobody"}), ${heldBlocks.size} held, game ${if (socket != null) "connected" else "NOT connected"}")
            // Our game may still wait for the old partner: answer for them, and tell any link
            // exchange waiting on them that they are gone.
            repeat((sent - received).coerceAtLeast(0)) { writeToGame(NO_PARTNER) }
            if (old != null) writeToGame(NO_PARTNER_BLOCK)
            sent = 0
            received = 0
            peer = id
            restartStreams()
            // Linked now: blocks our game sent a moment ago go to the partner.
            if (id != null) {
                heldBlocks.forEach { (_, block) -> sendToPeer(block) }
                heldBlocks.clear()
            }
        }
    }

    /**
     * Our game left the link (its trade or battle ended, or never got going): the partner's
     * game, if it still waits for ours, gives up instead of waiting on.
     */
    fun gameLeftLink() {
        synchronized(lock) {
            LinkLog.add("bridge: our game left the link${if (peer == null) " (nobody linked)" else ", telling $peer"}")
            if (peer != null) sendToPeer(GAME_LEFT)
        }
    }

    /** The room connection is back: whatever the peer may not have yet goes again now. */
    fun resendNow() {
        synchronized(lock) {
            LinkLog.add("bridge: room connection back, ${unacked.size} not yet confirmed")
            resend(force = true)
        }
    }

    /** A message from another player in the room. */
    fun fromPeer(
        from: Int,
        data: ByteArray,
    ) {
        synchronized(lock) {
            if (from != peer || data.size < FRAME_HEADER || data[0] != FRAME_MARK) {
                if (ignored++ < LOG_FIRST) LinkLog.add("bridge: ignored ${data.size} bytes from $from (linked with ${peer ?: "nobody"})")
                return
            }
            val frameStream = readInt(data, 2)
            val seq = readInt(data, 6)
            when (data[1]) {
                FRAME_ACK -> {
                    if (frameStream != stream) return
                    while (unacked.isNotEmpty() && unacked.first().seq <= seq) unacked.removeFirst()
                }

                FRAME_DATA -> {
                    if (frameStream != peerStream) {
                        // Their numbering starts over (a new link, or their app started again):
                        // wait for its first message, then start ours over too.
                        if (seq != 0) {
                            if (ignored++ < LOG_FIRST) LinkLog.add("bridge: partner's message #$seq of a numbering we haven't seen start")
                            return
                        }
                        LinkLog.add("bridge: partner's numbering starts${if (peerStream != null) " over (theirs restarted)" else ""}")
                        if (peerStream != null) restartOurStream()
                        peerStream = frameStream
                        expected = 0
                    }
                    if (seq == expected) {
                        expected++
                        deliver(data.copyOfRange(FRAME_HEADER, data.size))
                    } else if (seq > expected) {
                        LinkLog.add("bridge: partner's #$seq came before #$expected (waiting for it again)")
                    }
                    // What we have so far (repeats too, so they stop sending it).
                    if (expected > 0) outgoing.offer(roomPacket(from, frame(FRAME_ACK, frameStream, expected - 1)))
                }
            }
        }
    }

    /**
     * Next room packet to send: [0 = data][target id, 2 bytes][message], the same shape the
     * other link transports produce. Returns null on timeout or when stopped.
     */
    fun takeOutgoing(timeoutMs: Int): ByteArray? {
        val packet = outgoing.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return null
        return packet.takeIf { it.isNotEmpty() }
    }

    // A message from the peer, in order, once: to our game.
    private fun deliver(message: ByteArray) {
        if (message.contentEquals(GAME_LEFT)) {
            LinkLog.add("bridge: the partner's game left the link")
            writeToGame(NO_PARTNER_BLOCK)
            return
        }
        if (isBlock(message)) {
            LinkLog.add("bridge: partner's ${describe(message)} -> game${if (socket == null) " (game NOT connected: lost)" else ""}")
            // Padded to whole 2-byte messages, as Gambatte reads them.
            writeToGame(if (message.size % 2 == 0) message else message + 0)
            return
        }
        if (message.size != MESSAGE_SIZE) return
        if (socket == null) {
            // Our game isn't connected (yet): answer like an unplugged cable.
            LinkLog.add("bridge: partner's byte, but our game isn't connected")
            sendToPeer(NO_PARTNER)
            return
        }
        received++
        if (received <= LOG_FIRST || received % 100 == 0) LinkLog.add("bridge: partner's byte #$received -> game")
        writeToGame(message)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client =
                try {
                    server.accept()
                } catch (e: IOException) {
                    if (running) Timber.w(e, "Game Boy link accept failed")
                    return
                }
            client.tcpNoDelay = true
            synchronized(lock) {
                runCatching { socket?.close() }
                socket = client
                sent = 0
                received = 0
            }
            LinkLog.add("bridge: the game connected to the link")
            Thread({ readLoop(client) }, "gb-link-read").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun readLoop(client: Socket) {
        val input = DataInputStream(client.getInputStream())
        val message = ByteArray(MESSAGE_SIZE)
        try {
            while (running) {
                input.readFully(message)
                if (message[1] == BLOCK_MARK) {
                    val header = ByteArray(2)
                    input.readFully(header)
                    val length = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
                    val body = ByteArray(length + (length and 1))
                    input.readFully(body)
                    val block = message + header + body.copyOf(length)
                    synchronized(lock) {
                        LinkLog.add("bridge: game's ${describe(block)} -> ${peer?.let { "partner $it (#$nextSeq)" } ?: "held (nobody linked yet)"}")
                        if (peer == null) heldBlocks += now() to block else sendToPeer(block)
                    }
                    continue
                }
                synchronized(lock) {
                    if (peer == null) {
                        if (unplugged++ < LOG_FIRST) LinkLog.add("bridge: game's byte while nobody is linked: answered 0xFF")
                        writeToGame(NO_PARTNER)
                    } else {
                        sent++
                        if (sent <= LOG_FIRST || sent % 100 == 0) LinkLog.add("bridge: game's byte #$sent -> partner")
                        sendToPeer(message.copyOf())
                    }
                }
            }
        } catch (e: IOException) {
            LinkLog.add("bridge: the game closed the link (${e.javaClass.simpleName})")
        } finally {
            synchronized(lock) {
                if (socket === client) socket = null
            }
            runCatching { client.close() }
        }
    }

    // Held blocks nobody came for get "no partner"; messages the peer hasn't confirmed go again.
    private fun timerLoop() {
        while (running) {
            Thread.sleep(TIMER_MS)
            synchronized(lock) {
                val expired = heldBlocks.filter { now() - it.first > holdBlocksMs }
                if (expired.isNotEmpty()) {
                    LinkLog.add("bridge: ${expired.size} held block(s): nobody linked in time, the game hears 'no partner'")
                    heldBlocks.removeAll(expired.toSet())
                    expired.forEach { writeToGame(NO_PARTNER_BLOCK) }
                }
                resend(force = false)
            }
        }
    }

    // Call with the lock held.
    private fun sendToPeer(message: ByteArray) {
        val target = peer ?: return
        val frame = frame(FRAME_DATA, stream, nextSeq) + message
        unacked.addLast(Unacked(nextSeq, frame, now()))
        nextSeq++
        // A peer that never answers (gone for good) can't make us keep everything forever.
        while (unacked.size > MAX_UNACKED) unacked.removeFirst()
        outgoing.offer(roomPacket(target, frame))
    }

    // Call with the lock held. Sends again, in order, everything the peer hasn't confirmed
    // once the oldest has waited [resendMs] (or at once with [force]).
    private fun resend(force: Boolean) {
        val target = peer ?: return
        val oldest = unacked.firstOrNull() ?: return
        val now = now()
        if (!force && now - oldest.sentAt < resendMs) return
        if (resends++ < LOG_FIRST || resends % 20 == 0) LinkLog.add("bridge: sending ${unacked.size} again (oldest #${oldest.seq}, not confirmed)")
        unacked.forEach {
            it.sentAt = now
            outgoing.offer(roomPacket(target, it.frame))
        }
    }

    // Call with the lock held: a new link starts both numberings over.
    private fun restartStreams() {
        restartOurStream()
        peerStream = null
        expected = 0
    }

    private fun restartOurStream() {
        stream = newStream()
        nextSeq = 0
        unacked.clear()
    }

    private fun now(): Long = System.nanoTime() / 1_000_000

    // Call with the lock held.
    private fun writeToGame(message: ByteArray) {
        val current = socket ?: return
        try {
            current.getOutputStream().apply {
                write(message)
                flush()
            }
        } catch (e: IOException) {
            LinkLog.add("bridge: couldn't write to the game (${e.javaClass.simpleName})")
            runCatching { current.close() }
            socket = null
        }
    }

    companion object {
        // The ports Gambatte's link option offers.
        val DEFAULT_PORTS = 56400..56420

        // Where Gambatte connects: 127.0.0.1 (see coreVariables). Not getLoopbackAddress(): on
        // Android that is the IPv6 one (::1), where Gambatte's IPv4 connection finds nobody
        // ("connection refused"), so the game never reached the bridge.
        val GAME_ADDRESS: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        const val MESSAGE_SIZE = 2

        // What a Game Boy reads with nothing plugged in.
        private val NO_PARTNER = byteArrayOf(0xFF.toByte(), 0x80.toByte())

        // How long a block sent before we are linked waits for the partner.
        const val HOLD_BLOCKS_MS = 10_000L

        // How long a message waits for the peer to confirm it before it goes again.
        const val RESEND_MS = 1_500L
        private const val TIMER_MS = 200L
        private const val MAX_UNACKED = 20_000

        // Block messages (see the class comment); the marks match our Gambatte's.
        const val BLOCK_MARK: Byte = 0xB5.toByte()
        private val NO_PARTNER_BLOCK = byteArrayOf(0, 0xB6.toByte())

        // Between bridges only: "my game left the link".
        private val GAME_LEFT = byteArrayOf(0, 0xB7.toByte())

        // Between bridges every message is framed: [FRAME_MARK, kind, stream (4 bytes),
        // number (4 bytes)], then the message (for DATA). An ACK confirms every number up to
        // its own.
        private const val FRAME_MARK: Byte = 0xA7.toByte()
        private const val FRAME_DATA: Byte = 1
        private const val FRAME_ACK: Byte = 2
        private const val FRAME_HEADER = 10

        fun isBlock(data: ByteArray): Boolean = data.size >= 4 && data[1] == BLOCK_MARK

        private const val LOG_FIRST = 5
        private val KINDS = mapOf(1 to "exchange", 2 to "ready check", 3 to "battle move", 4 to "friend meeting")

        // A block for the link log: its kind (our Gambatte's first byte) and size.
        private fun describe(block: ByteArray): String {
            val length = block.size - 4
            val kind = if (length > 0) block[4].toInt() and 0xFF else -1
            return "${KINDS[kind] ?: "block kind $kind"} ($length bytes)"
        }

        private fun newStream(): Int = Random.nextInt().let { if (it == 0) 1 else it }

        private fun frame(
            kind: Byte,
            stream: Int,
            seq: Int,
        ): ByteArray =
            byteArrayOf(
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

        fun roomPacket(
            target: Int,
            message: ByteArray,
        ): ByteArray =
            byteArrayOf(0, (target shr 8).toByte(), target.toByte()) + message

        /** Core options that plug Gambatte into this bridge on [port]. */
        fun coreVariables(port: Int): List<Pair<String, String>> {
            // Gambatte reads the address as 12 digits, three per part: 127.000.000.001.
            val digits = "127000000001"
            return listOf(
                "gambatte_gb_link_mode" to "Network Client",
                "gambatte_gb_link_network_port" to port.toString(),
            ) + digits.mapIndexed { index, digit -> "gambatte_gb_link_network_server_ip_${index + 1}" to digit.toString() }
        }

        val DISCONNECT_VARIABLES = listOf("gambatte_gb_link_mode" to "Not Connected")
    }
}
