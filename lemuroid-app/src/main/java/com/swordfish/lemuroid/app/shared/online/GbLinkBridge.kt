package com.swordfish.lemuroid.app.shared.online

import timber.log.Timber
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
 */
class GbLinkBridge(
    private val ports: IntRange = DEFAULT_PORTS,
    private val holdBlocksMs: Long = HOLD_BLOCKS_MS,
) {
    private val lock = Any()
    private val outgoing = LinkedBlockingQueue<ByteArray>()

    @Volatile private var running = false
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var peer: Int? = null

    // Messages since the current link started: sent by our game, and received from the peer.
    // If the link breaks while our game still waits for answers, we answer for the peer.
    private var sent = 0
    private var received = 0

    // Our blocks still waiting for the peer's.
    private var blocksOwed = 0

    // Blocks our game sent while unlinked, with when (SystemClock-free: System.nanoTime ms).
    private val heldBlocks = ArrayList<Pair<Long, ByteArray>>()

    // The partner's blocks that came while our game wasn't plugged into the bridge (it reconnects
    // after its link options change), with when: they go to the game once it is back.
    private val blocksForGame = ArrayList<Pair<Long, ByteArray>>()

    /**
     * What went over the cable since [markAttempt]: our game's blocks, the partner's, and the
     * partner's that never reached our game. Tells why a trade or battle didn't start.
     */
    data class AttemptStats(
        val blocksFromGame: Int,
        val blocksFromPeer: Int,
        val blocksLost: Int,
    )

    private var attemptFromGame = 0
    private var attemptFromPeer = 0
    private var attemptLost = 0

    /** Starts listening and returns the port Gambatte should connect to, or null. */
    fun start(): Int? {
        synchronized(lock) {
            if (running) return server?.localPort
            for (port in ports) {
                val candidate =
                    runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()) }.getOrNull() ?: continue
                server = candidate
                running = true
                Thread({ acceptLoop(candidate) }, "gb-link-accept").apply {
                    isDaemon = true
                    start()
                }
                Thread({ heldBlocksLoop() }, "gb-link-held").apply {
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
            blocksForGame.clear()
        }
        outgoing.clear()
        // Wake the room's sending thread.
        outgoing.offer(ByteArray(0))
    }

    /** The room player our cable is plugged into, or null when unplugged. */
    fun setPeer(id: Int?) {
        synchronized(lock) {
            if (id == peer) return
            val owed = sent - received
            repeat(owed.coerceAtLeast(0)) { writeToGame(NO_PARTNER) }
            repeat(blocksOwed.coerceAtLeast(0)) { writeToGame(NO_PARTNER_BLOCK) }
            sent = 0
            received = 0
            blocksOwed = 0
            blocksForGame.clear()
            peer = id
            // Linked now: blocks our game sent a moment ago go to the partner.
            if (id != null) {
                heldBlocks.forEach { (_, block) ->
                    blocksOwed++
                    outgoing.offer(roomPacket(id, block))
                }
                heldBlocks.clear()
            }
        }
    }

    /** A message from another player in the room. */
    fun fromPeer(
        from: Int,
        data: ByteArray,
    ) {
        if (isBlock(data)) {
            synchronized(lock) {
                if (from != peer) return
                blocksOwed--
                attemptFromPeer++
                // Padded to whole 2-byte messages, as Gambatte reads them.
                val padded = if (data.size % 2 == 0) data else data + 0
                if (socket == null) blocksForGame += now() to padded else writeToGame(padded)
            }
            return
        }
        if (data.size != MESSAGE_SIZE) return
        synchronized(lock) {
            if (from != peer) return
            if (socket == null) {
                // Our game isn't connected (yet): answer like an unplugged cable.
                outgoing.offer(roomPacket(from, NO_PARTNER))
                return
            }
            received++
            writeToGame(data)
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
                // The partner's blocks that came while the game was away.
                val fresh = blocksForGame.filter { now() - it.first <= holdBlocksMs }
                attemptLost += blocksForGame.size - fresh.size
                blocksForGame.clear()
                fresh.forEach { writeToGame(it.second) }
            }
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
                        attemptFromGame++
                        val target = peer
                        if (target == null) {
                            heldBlocks += now() to block
                        } else {
                            blocksOwed++
                            outgoing.offer(roomPacket(target, block))
                        }
                    }
                    continue
                }
                synchronized(lock) {
                    val target = peer
                    if (target == null) {
                        writeToGame(NO_PARTNER)
                    } else {
                        sent++
                        outgoing.offer(roomPacket(target, message.copyOf()))
                    }
                }
            }
        } catch (e: IOException) {
            Timber.i("Game Boy link closed by the emulator")
        } finally {
            synchronized(lock) {
                if (socket === client) socket = null
            }
            runCatching { client.close() }
        }
    }

    // Held blocks nobody came for get "no partner".
    private fun heldBlocksLoop() {
        while (running) {
            Thread.sleep(HELD_CHECK_MS)
            synchronized(lock) {
                val expired = heldBlocks.filter { now() - it.first > holdBlocksMs }
                if (expired.isNotEmpty()) {
                    heldBlocks.removeAll(expired.toSet())
                    expired.forEach { writeToGame(NO_PARTNER_BLOCK) }
                }
                // Stale by now: a game that comes back later must not take them for new ones.
                val stale = blocksForGame.filter { now() - it.first > holdBlocksMs }
                if (stale.isNotEmpty()) {
                    blocksForGame.removeAll(stale.toSet())
                    attemptLost += stale.size
                }
            }
        }
    }

    /** Starts counting for a new trade or battle (see [attemptStats]). */
    fun markAttempt() {
        synchronized(lock) {
            attemptFromGame = 0
            attemptFromPeer = 0
            attemptLost = 0
        }
    }

    fun attemptStats(): AttemptStats =
        synchronized(lock) { AttemptStats(attemptFromGame, attemptFromPeer, attemptLost) }

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
            Timber.w(e, "Could not write to the Game Boy link")
            runCatching { current.close() }
            socket = null
        }
    }

    companion object {
        // The ports Gambatte's link option offers.
        val DEFAULT_PORTS = 56400..56420

        const val MESSAGE_SIZE = 2

        // What a Game Boy reads with nothing plugged in.
        private val NO_PARTNER = byteArrayOf(0xFF.toByte(), 0x80.toByte())

        // How long a block sent before we are linked waits for the partner.
        const val HOLD_BLOCKS_MS = 10_000L
        private const val HELD_CHECK_MS = 200L

        // Block messages (see the class comment); the marks match our Gambatte's.
        const val BLOCK_MARK: Byte = 0xB5.toByte()
        private val NO_PARTNER_BLOCK = byteArrayOf(0, 0xB6.toByte())

        fun isBlock(data: ByteArray): Boolean = data.size >= 4 && data[1] == BLOCK_MARK

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
