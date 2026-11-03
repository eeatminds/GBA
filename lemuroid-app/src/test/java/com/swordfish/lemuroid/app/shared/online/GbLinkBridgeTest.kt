package com.swordfish.lemuroid.app.shared.online

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    private fun bridge(ports: IntRange): Pair<GbLinkBridge, Int> {
        val bridge = GbLinkBridge(ports)
        val port = bridge.start()
        assertNotNull(port)
        bridges += bridge
        return bridge to port!!
    }

    private fun emulator(port: Int): Socket = Socket(InetAddress.getLoopbackAddress(), port).also {
        it.soTimeout = 3000
        sockets += it
    }

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
                if (packet[0].toInt() == 0 && target == toId) to.fromPeer(fromId, packet.copyOfRange(3, packet.size))
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun Socket.send(vararg bytes: Int) {
        getOutputStream().write(bytes.map { it.toByte() }.toByteArray())
        getOutputStream().flush()
    }

    private fun Socket.receive(): ByteArray = ByteArray(2).also { DataInputStream(getInputStream()).readFully(it) }

    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun `unplugged cable answers 0xFF`() {
        val (_, port) = bridge(56500..56510)
        val game = emulator(port)
        game.send(0x42, 0)
        assertArrayEquals(bytes(0xFF, 0), game.receive())
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
        assertArrayEquals(bytes(0xFF, 0), gameA.receive())
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
        // [type 0][target 1][the block, unpadded]
        assertArrayEquals(bytes(0, 0, 1, 0, 0xB5, 451 and 0xFF, 451 shr 8), packet.copyOfRange(0, 7))
        assertEquals(3 + 4 + 451, packet.size)
        b.fromPeer(0, packet.copyOfRange(3, packet.size))
        assertArrayEquals(bytes(*block(*body)), gameB.receive(block(*body).size))
    }

    @Test
    fun `a block with nobody linked gets no partner at once`() {
        val (_, port) = bridge(56571..56580)
        val game = emulator(port)
        Thread.sleep(200)
        game.send(*block(2, 5))
        assertArrayEquals(bytes(0, 0xB6), game.receive())
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
