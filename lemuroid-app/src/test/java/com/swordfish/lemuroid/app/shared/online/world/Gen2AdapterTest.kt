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
    fun `covered by the start menu and text boxes, not by turning`() {
        walkingRight()
        assertEquals(false, adapter.read(memory)!!.covered)
        // Turning on the spot runs a tiny script.
        put(layout.scriptRunning, 9)
        assertEquals(false, adapter.read(memory)!!.covered)
        // Talking to someone or reading a sign.
        put(layout.scriptRunning, 0xFF)
        assertEquals(true, adapter.read(memory)!!.covered)
        // The start menu (it also runs a script).
        put(layout.windowStackSize, 1)
        val avatar = adapter.read(memory)!!
        assertEquals("24.4", avatar.map)
        assertEquals(true, avatar.covered)
    }

    @Test
    fun `only English versions are recognised`() {
        assertEquals(Gen2Adapter.CRYSTAL, Gen2Adapter.layoutFor("PM_CRYSTAL\u0000", "BYTE"))
        assertEquals(Gen2Adapter.GOLD_SILVER, Gen2Adapter.layoutFor("POKEMON_SLV", "AAXE"))
        assertNull(Gen2Adapter.layoutFor("PM_CRYSTAL\u0000", "BXTJ"))
    }

    @Test
    fun `no in-place link without the expected scripts in the ROM`() {
        assertNull(adapter.inPlaceLink(Talk.TRADE))
        assertNull(Gen2Adapter(Rom(ByteArray(0x200000), 0L), Gen2Adapter.GOLD_SILVER).inPlaceLink(Talk.TRADE))
    }

    // Gold and Silver: the scripts that traded and battled between a linked Gold and Silver.
    @Test
    fun `in-place trade and battle scripts for Gold and Silver`() {
        for (variable in listOf("GOLD_ROM", "SILVER_ROM")) {
            val file = System.getenv(variable)?.let(::File) ?: continue
            assumeTrue(file.exists())
            val real = WorldAdapters.forRom("gbc", readRom(file))!!
            val ram = GameMemory.SYSTEM_RAM_BASE - 0xC000
            val trade = real.inPlaceLink(Talk.TRADE)!!.writes
            assertEquals(ram + 0xC6E8, trade[0].first)
            assertEquals("0f0100 0f0200 08b454 0f0400 08b454 0f0300 08b454 03af54".replace(" ", ""), trade[0].second.hex())
            assertEquals(ram + 0xD184, trade[1].first)
            assertEquals("5ce8c6", trade[1].second.hex())
            assertEquals(ram + 0xD183, trade[2].first)
            assertEquals("0f0900 0f0200 083555 0f0400 083555 0f0300 083555 033055".replace(" ", ""), real.inPlaceLink(Talk.BATTLE)!!.writes[0].second.hex())
            assertEquals("0f080090", real.inPlaceLinkCleanup()!!.writes[0].second.hex())
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun readRom(file: File): ByteArray =
        if (file.name.endsWith(".zip")) {
            ZipFile(file).use { zip -> zip.getInputStream(zip.entries().toList().first { it.name.endsWith(".gbc") }).readBytes() }
        } else {
            file.readBytes()
        }

    // The exact bytes that started a trade and a battle between two linked Crystal games in a
    // desktop emulator (both sides reached the trade menu / the battle, and came back in place).
    @Test
    fun `in-place trade and battle scripts for Crystal`() {
        val file = System.getenv("CRYSTAL_ROM")?.let(::File)
        assumeTrue(file != null && file.exists())
        val rom =
            if (file!!.name.endsWith(".zip")) {
                ZipFile(file).use { zip -> zip.getInputStream(zip.entries().toList().first { it.name.endsWith(".gbc") }).readBytes() }
            } else {
                file.readBytes()
            }
        val real = WorldAdapters.forRom("gbc", rom)!!
        val ram = GameMemory.SYSTEM_RAM_BASE - 0xC000

        val trade = real.inPlaceLink(Talk.TRADE)!!.writes
        assertEquals(ram + 0xC7E8, trade[0].first)
        assertEquals(
            "0f0100 0f0200 080a74 0f0400 080a74 0f0300 080a74 030574".replace(" ", ""),
            trade[0].second.joinToString("") { "%02x".format(it) },
        )
        assertEquals(ram + 0xD45D, trade[1].first)
        assertEquals("64e8c7", trade[1].second.joinToString("") { "%02x".format(it) })
        // The flag that makes the game run it comes last.
        assertEquals(ram + 0xD45C, trade[2].first)
        assertEquals("01", trade[2].second.joinToString("") { "%02x".format(it) })

        val battle = real.inPlaceLink(Talk.BATTLE)!!.writes
        assertEquals(
            "0f0900 0f0200 089874 0f0400 089874 0f0300 089874 039374".replace(" ", ""),
            battle[0].second.joinToString("") { "%02x".format(it) },
        )
        assertEquals("0f080091", real.inPlaceLinkCleanup()!!.writes[0].second.joinToString("") { "%02x".format(it) })

        // Applied to a work RAM block, the script lands where the game will read it.
        val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ByteBuffer.allocate(0x8000))))
        assertTrue(real.inPlaceLink(Talk.TRADE)!!.applyTo(memory))
        assertEquals(1, memory.u8(ram + 0xD45C))
        assertEquals(0x0F, memory.u8(ram + 0xC7E8))
        assertEquals(0x74, memory.u8(ram + 0xC7E8 + 23))
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
