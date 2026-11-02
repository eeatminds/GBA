package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

class Gen2AdapterTest {
    private val wram = ByteBuffer.allocate(0x8000)
    private val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, wram)))
    private val layout = Gen2Adapter.CRYSTAL
    private val adapter = Gen2Adapter(Rom(ByteArray(0x8000), 0L), layout)

    private fun put(
        address: Int,
        value: Int,
    ) = wram.put(address - 0xC000, value.toByte())

    // Chris on map 24.4, tile (6, 5), three pixels into a step to the right.
    private fun walkingRight() {
        put(layout.mapStatus, 2)
        put(layout.battleMode, 0)
        put(layout.playerStruct + 0x00, 1)
        put(layout.playerStruct + 0x04, 0)
        put(layout.playerStruct + 0x06, 0)
        put(layout.playerStruct + 0x0D, 12)
        put(layout.playerStruct + 0x17, 0x43)
        put(layout.playerStruct + 0x18, 0x44)
        put(layout.mapGroup, 24)
        put(layout.mapNumber, 4)
        put(layout.xCoord, 6)
        put(layout.yCoord, 5)
        put(layout.stepFlags, 0x40)
        put(layout.stepDirection, 3)
        put(layout.bgMapOffsetX, -3)
        put(layout.bgMapOffsetY, 0)
    }

    @Test
    fun `reads map, position mid-step and the sprite`() {
        walkingRight()
        val avatar = adapter.read(memory)!!
        assertEquals("24.4", avatar.map)
        // The tile only changes when the step ends; the scroll says 3 pixels so far.
        assertEquals(6 * 16 + 3, avatar.x)
        assertEquals(5 * 16, avatar.y)
        assertEquals(Look(1, 12, false), avatar.look)
        // The game draws the sprite at sprite x + offset + scroll, 4 pixels higher.
        assertEquals(0x43 - 3, avatar.screenX)
        assertEquals(0x44 - 4, avatar.screenY)
    }

    @Test
    fun `steps up and left subtract`() {
        walkingRight()
        put(layout.stepDirection, 2)
        put(layout.bgMapOffsetX, 5)
        assertEquals(6 * 16 - 5, adapter.read(memory)!!.x)
        put(layout.stepDirection, 1)
        put(layout.bgMapOffsetY, 7)
        assertEquals(5 * 16 - 7, adapter.read(memory)!!.y)
        // Standing still: the scroll rests on a whole tile.
        put(layout.stepFlags, 0)
        put(layout.bgMapOffsetY, 16)
        assertEquals(5 * 16, adapter.read(memory)!!.y)
    }

    @Test
    fun `nothing in battle, while loading a map or when hidden`() {
        walkingRight()
        put(layout.battleMode, 1)
        assertNull(adapter.read(memory))
        walkingRight()
        put(layout.mapStatus, 1)
        assertNull(adapter.read(memory))
        walkingRight()
        put(layout.playerStruct + 0x04, 1)
        assertNull(adapter.read(memory))
    }

    @Test
    fun `only English versions are recognised`() {
        assertEquals(Gen2Adapter.CRYSTAL, Gen2Adapter.layoutFor("PM_CRYSTAL\u0000", "BYTE"))
        assertEquals(Gen2Adapter.GOLD_SILVER, Gen2Adapter.layoutFor("POKEMON_SLV", "AAXE"))
        assertNull(Gen2Adapter.layoutFor("PM_CRYSTAL\u0000", "BXTJ"))
    }

    // Uses a real Crystal ROM (or a zip with one) when CRYSTAL_ROM points to it.
    @Test
    fun `draws Chris and Kris from the game's own graphics`() {
        val file = System.getenv("CRYSTAL_ROM")?.let(::File)
        assumeTrue(file != null && file.exists())
        val rom =
            if (file!!.name.endsWith(".zip")) {
                ZipFile(file).use { zip -> zip.getInputStream(zip.entries().toList().first { it.name.endsWith(".gbc") }).readBytes() }
            } else {
                file.readBytes()
            }
        val real = WorldAdapters.forRom("gbc", rom)
        assertNotNull(real)
        assertEquals("johto-c", real!!.world)
        // Chris (red palette 0) and Kris (blue palette 1), on foot and on the bike.
        for (sprite in listOf(0x01, 0x02, 0x60 or (1 shl 8), 0x61 or (1 shl 8))) {
            for (facing in 0 until 16) {
                val picture = real.sprite(Look(sprite, facing, false))
                assertNotNull("sprite $sprite facing $facing", picture)
                assertEquals(16, picture!!.width)
                assertTrue(picture.pixels.count { it != 0 } > 60)
            }
        }
    }
}
