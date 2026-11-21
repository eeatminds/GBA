package com.swordfish.lemuroid.app.shared.online.world

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

/**
 * One in-place trade or battle at a time. Two phones that said yes twice in a row (a battle,
 * then a trade over it) had the game run a mix of both scripts from the same place in its
 * memory: the trade went through, then the game crashed to its "Game Boy Color only" screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class InPlaceLinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ram = ByteBuffer.allocate(0x8000)
    private val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ram)))

    // Writes its script to $C000 and marks it queued at $C100; the game is busy while either is set.
    private val adapter =
        object : WorldAdapter {
            override val world: String = "test"
            override val screenWidth: Int = 160
            override val screenHeight: Int = 144

            override fun read(memory: GameMemory): Avatar? = null

            override fun sprite(look: Look): SpriteImage? = null

            override fun inPlaceLink(kind: String): MemoryPatch =
                MemoryPatch(
                    listOf(
                        GameMemory.SYSTEM_RAM_BASE to byteArrayOf(if (kind == Talk.TRADE) 1 else 2),
                        GameMemory.SYSTEM_RAM_BASE + 0x100 to byteArrayOf(1),
                    ),
                )

            override fun scriptPending(memory: GameMemory): Boolean = (memory.u8(GameMemory.SYSTEM_RAM_BASE + 0x100) ?: 0) != 0
        }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `a second yes while a link is going starts nothing`() {
        val world = WorldSession(adapter, { memory }, send = { }, scope = scope)
        assertTrue(world.startInPlace(Talk.BATTLE))
        assertTrue(world.linking)
        assertFalse(world.startInPlace(Talk.TRADE))
        // The battle's script is still the one in the game's memory.
        assertEquals(2, memory.u8(GameMemory.SYSTEM_RAM_BASE))
    }

    @Test
    fun `nothing starts while the game runs a script of its own`() {
        ram.put(0x100, 1)
        val world = WorldSession(adapter, { memory }, send = { }, scope = scope)
        assertFalse(world.startInPlace(Talk.TRADE))
        assertFalse(world.linking)
    }
}
