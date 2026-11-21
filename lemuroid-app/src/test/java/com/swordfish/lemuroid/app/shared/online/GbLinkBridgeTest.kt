package com.swordfish.lemuroid.app.shared.online

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Two bridges joined by a pretend room server, each with a socket standing in for Gambatte.
 * Gambatte's link sends 2 bytes and (when it drives the transfer) waits for 2 back.
 */
class GbLinkBridgeTest {
    private val running = AtomicBoolean(true)
    private val bridges = mutableListOf<GbLinkBridge>()
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        running.set(false)
        sockets.forEach { runCatching { it.close() } }
        bridges.forEach { it.stop() }
    }

    private fun bridge(
        ports: IntRange,
        holdBlocksMs: Long = 300,
    ): Pair<GbLinkBridge, Int> {
        val bridge = GbLinkBridge(ports, holdBlocksMs, resendMs = 200)
        val port = bridge.start()
        assertNotNull(port)
        bridges += bridge
        return bridge to port!!
    }

    // Connects as Gambatte does: to 127.0.0.1 (IPv4), whatever this system's usual loopback is.
    private fun emulator(port: Int): Socket = Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).also {
        it.soTimeout = 3000
        sockets += it
    }

    // While set, the pretend server loses everything (a phone's connection is down).
    private val down = AtomicBoolean(false)

    // While set, it delivers everything twice.
    private val twice = AtomicBoolean(false)

    // Moves packets one bridge sends to the other, like the room server does.
    private fun relay(
        from: GbLinkBridge,
        fromId: Int,
        to: GbLinkBridge,
        toId: Int,
    ) {
        Thread {
            while (running.get()) {
                val packet = from.takeOutgoing(50) ?: continue
                val target = ((packet[1].toInt() and 0xFF) shl 8) or (packet[2].toInt() and 0xFF)
                if (packet[0].toInt() != 0 || target != toId || down.get()) continue
                repeat(if (twice.get()) 2 else 1) { to.fromPeer(fromId, packet.copyOfRange(3, packet.size)) }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // Two linked bridges with a game on each.
    private fun linkedPair(
        portsA: IntRange,
        portsB: IntRange,
    ): Pair<Socket, Socket> {
        val (a, portA) = bridge(portsA)
        val (b, portB) = bridge(portsB)
        val gameA = emulator(portA)
        val gameB = emulator(portB)
        // Give the bridges a moment to accept the emulators.
        Thread.sleep(200)
        relay(a, 0, b, 1)
        relay(b, 1, a, 0)
        a.setPeer(1)
        b.setPeer(0)
        return gameA to gameB
    }

    private fun Socket.send(vararg bytes: Int) {
        getOutputStream().write(bytes.map { it.toByte() }.toByteArray())
        getOutputStream().flush()
    }

    private fun Socket.receive(): ByteArray = ByteArray(2).also { DataInputStream(getInputStream()).readFully(it) }

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    // On Android the usual loopback is ::1 (IPv6); Gambatte connects to the IPv4 address it is
    // given. Listening anywhere else, the game never reaches the bridge ("connection refused").
    @Test
    fun `the bridge listens exactly where Gambatte is told to connect`() {
        val digits =
            GbLinkBridge.coreVariables(56400)
                .filter { it.first.startsWith("gambatte_gb_link_network_server_ip_") }
                .joinToString("") { it.second }
        val address = digits.chunked(3).joinToString(".") { it.toInt().toString() }
        assertEquals("127.0.0.1", address)
        assertEquals(address, GbLinkBridge.GAME_ADDRESS.hostAddress)
    }

    @Test
    fun `unplugged cable answers 0xFF`() {
        val (_, port) = bridge(56500..56510)
        val game = emulator(port)
        game.send(0x42, 0)
        // An answer (flag 0x80), so a late one is never taken for a new transfer.
        assertArrayEquals(bytes(0xFF, 0x80), game.receive())
    }

    @Test
    fun `linked games swap bytes through the room`() {
        val (a, portA) = bridge(56511..56520)
        val (b, portB) = bridge(56521..56530)
        val gameA = emulator(portA)
        val gameB = emulator(portB)
        // Give the bridges a moment to accept the emulators.
        Thread.sleep(200)
        relay(a, 0, b, 1)
        relay(b, 1, a, 0)
        a.setPeer(1)
        b.setPeer(0)

        // Game A drives a transfer; game B sees it and answers with its own byte.
        gameA.send(0x10, 1)
        assertArrayEquals(bytes(0x10, 1), gameB.receive())
        gameB.send(0x20, 0x80)
        assertArrayEquals(bytes(0x20, 0x80), gameA.receive())

        // The other way round.
        gameB.send(0x55, 0)
        assertArrayEquals(bytes(0x55, 0), gameA.receive())
        gameA.send(0x66, 0x80)
        assertArrayEquals(bytes(0x66, 0x80), gameB.receive())
    }

    @Test
    fun `unplugging while a game waits answers for the missing partner`() {
        val (a, portA) = bridge(56531..56540)
        val (b, _) = bridge(56541..56550)
        val gameA = emulator(portA)
        Thread.sleep(200)
        a.setPeer(1)
        // Game A sends, but nothing comes back before the cable is unplugged.
        gameA.send(0x33, 0)
        Thread.sleep(100)
        assertNotNull(a.takeOutgoing(500))
        a.setPeer(null)
        assertArrayEquals(bytes(0xFF, 0x80), gameA.receive())
        // And a link exchange waiting on them hears they are gone.
        assertArrayEquals(bytes(0, 0xB6), gameA.receive())
        b.stop()
    }

    private fun Socket.receive(count: Int): ByteArray = ByteArray(count).also { DataInputStream(getInputStream()).readFully(it) }

    // A block as our Gambatte frames it: [0, 0xB5, length low, length high, bytes] padded to even.
    private fun block(vararg body: Int): IntArray {
        val frame = intArrayOf(0, 0xB5, body.size and 0xFF, body.size shr 8) + body
        return if (frame.size % 2 == 0) frame else frame + 0
    }

    @Test
    fun `a whole block crosses as one packet and arrives intact`() {
        val (a, portA) = bridge(56551..56560)
        val (b, portB) = bridge(56561..56570)
        val gameA = emulator(portA)
        val gameB = emulator(portB)
        Thread.sleep(200)
        a.setPeer(1)
        b.setPeer(0)
        val body = IntArray(451) { (it * 7) and 0xFF }
        gameA.send(*block(*body))
        val packet = a.takeOutgoing(1000)!!
        // [type 0][target 1][numbered: mark, DATA, stream, number 0][the block, unpadded]
        assertArrayEquals(bytes(0, 0, 1, 0xA7, 1), packet.copyOfRange(0, 5))
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0xB5, 451 and 0xFF, 451 shr 8), packet.copyOfRange(9, 17))
        assertEquals(3 + 10 + 4 + 451, packet.size)
        b.fromPeer(0, packet.copyOfRange(3, packet.size))
        assertArrayEquals(bytes(*block(*body)), gameB.receive(block(*body).size))
    }

    @Test
    fun `blocks lost while a connection is down arrive once it's back, once each and in order`() {
        val (gameA, gameB) = linkedPair(56601..56610, 56611..56620)
        down.set(true)
        gameA.send(*block(1, 1, 1))
        gameA.send(*block(2, 2))
        Thread.sleep(600)
        down.set(false)
        assertArrayEquals(bytes(*block(1, 1, 1)), gameB.receive(block(1, 1, 1).size))
        assertArrayEquals(bytes(*block(2, 2)), gameB.receive(block(2, 2).size))
        // The answer comes back the same way, and nothing is delivered twice.
        gameB.send(*block(3))
        assertArrayEquals(bytes(*block(3)), gameA.receive(block(3).size))
        gameA.send(*block(4))
        assertArrayEquals(bytes(*block(4)), gameB.receive(block(4).size))
        Thread.sleep(800)
        gameB.soTimeout = 300
        assertTrue(runCatching { gameB.receive() }.isFailure)
    }

    @Test
    fun `messages delivered twice by the network reach the game once`() {
        val (gameA, gameB) = linkedPair(56621..56630, 56631..56640)
        twice.set(true)
        gameA.send(*block(9, 8, 7, 6))
        gameA.send(0x42, 0)
        assertArrayEquals(bytes(*block(9, 8, 7, 6)), gameB.receive(block(9, 8, 7, 6).size))
        assertArrayEquals(bytes(0x42, 0), gameB.receive())
        gameB.soTimeout = 500
        assertTrue(runCatching { gameB.receive() }.isFailure)
    }

    @Test
    fun `when one game leaves the link, the other stops waiting`() {
        val (a, portA) = bridge(56641..56650)
        val (b, portB) = bridge(56651..56660)
        emulator(portA)
        val gameB = emulator(portB)
        Thread.sleep(200)
        relay(a, 0, b, 1)
        relay(b, 1, a, 0)
        a.setPeer(1)
        b.setPeer(0)
        gameB.send(*block(5, 5))
        a.gameLeftLink()
        assertArrayEquals(bytes(0, 0xB6), gameB.receive())
    }

    @Test
    fun `a block with nobody linked gets no partner after a while`() {
        val (_, port) = bridge(56571..56580, holdBlocksMs = 300)
        val game = emulator(port)
        Thread.sleep(200)
        game.send(*block(2, 5))
        val started = System.currentTimeMillis()
        assertArrayEquals(bytes(0, 0xB6), game.receive())
        assertTrue(System.currentTimeMillis() - started >= 250)
    }

    // The room links two players a moment after one accepts: a block sent just before waits.
    @Test
    fun `a block sent just before linking goes to the partner once linked`() {
        val (a, portA) = bridge(56591..56600, holdBlocksMs = 5_000)
        val gameA = emulator(portA)
        Thread.sleep(200)
        gameA.send(*block(4, 1, 2, 3, 4))
        Thread.sleep(300)
        assertNull(a.takeOutgoing(50))
        a.setPeer(7)
        val packet = a.takeOutgoing(1000)!!
        assertArrayEquals(bytes(0, 0, 7, 0xA7, 1), packet.copyOfRange(0, 5))
        assertArrayEquals(bytes(0, 0, 0, 0, 0, 0xB5, 5, 0, 4, 1, 2, 3, 4), packet.copyOfRange(9, packet.size))
    }

    @Test
    fun `unplugging while a block waits for the partner lets the game go`() {
        val (a, portA) = bridge(56581..56590)
        val gameA = emulator(portA)
        Thread.sleep(200)
        a.setPeer(1)
        gameA.send(*block(1, 2, 3))
        assertNotNull(a.takeOutgoing(1000))
        a.setPeer(null)
        assertArrayEquals(bytes(0, 0xB6), gameA.receive())
    }
}
