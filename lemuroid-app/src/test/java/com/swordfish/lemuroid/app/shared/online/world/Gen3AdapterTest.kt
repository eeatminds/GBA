package com.swordfish.lemuroid.app.shared.online.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Gen3AdapterTest {
    private val ewram = ByteBuffer.allocate(0x40000).order(ByteOrder.LITTLE_ENDIAN)
    private val iwram = ByteBuffer.allocate(0x8000).order(ByteOrder.LITTLE_ENDIAN)
    private val memory =
        GameMemory(
            listOf(
                GameMemory.Region(0x02000000, ewram),
                GameMemory.Region(0x03000000, iwram),
            ),
        )
    private val layout = Gen3Adapter.EMERALD
    private val adapter = Gen3Adapter(Rom(ByteArray(0x100), 0x08000000), layout)

    private fun put32(
        address: Long,
        value: Long,
    ) = buffer(address).putInt(offset(address), value.toInt())

    private fun put16(
        address: Long,
        value: Int,
    ) = buffer(address).putShort(offset(address), value.toShort())

    private fun put8(
        address: Long,
        value: Int,
    ) = buffer(address).put(offset(address), value.toByte())

    private fun buffer(address: Long) = if (address >= 0x03000000) iwram else ewram

    private fun offset(address: Long) = (address and 0xFFFFFF).toInt()

    // Emerald, standing on tile (10, 5) of map 0.9, having scrolled 4 pixels to the right.
    private fun walkingRight() {
        put32(layout.main + 4, layout.cb2Overworld or 1)
        val saveBlock = 0x02025a00L
        put32(layout.saveBlock1, saveBlock)
        put16(saveBlock, 10)
        put16(saveBlock + 2, 5)
        put8(saveBlock + 4, 0)
        put8(saveBlock + 5, 9)
        put32(layout.fieldCamera + 0x10, 4)
        put32(layout.fieldCamera + 0x14, 0)
        put8(layout.playerAvatar + 4, 3)
        put8(layout.playerAvatar + 5, 0)
        put8(layout.objectEvents + 5, 89)
        val sprite = layout.sprites + 3 * 0x44
        put16(sprite + 0x20, 120)
        put16(sprite + 0x22, 72)
        put8(sprite + 0x28, -8)
        put8(sprite + 0x29, -16)
        put8(sprite + 0x3E, 0x3)
        put16(layout.spriteCoordOffsetX, 0)
        put16(layout.spriteCoordOffsetY, -40)
    }

    @Test
    fun `reads position, map and sprite while walking`() {
        walkingRight()
        val avatar = adapter.read(memory)
        assertNotNull(avatar)
        avatar!!
        assertEquals("0.9", avatar.map)
        // The tile already moved to 10 at the start of the step; the view is 4 of 16 pixels in.
        assertEquals(10 * 16 + 4 - 16, avatar.x)
        assertEquals(5 * 16, avatar.y)
        assertEquals(89, avatar.look.sprite)
        assertEquals(120 - 8, avatar.screenX)
        assertEquals(72 - 16 - 40, avatar.screenY)
        assertEquals(false, avatar.look.flip)

        // Facing right: the game mirrors the left-facing frame through the OAM attributes.
        put32(layout.sprites + 3 * 0x44, 1L shl 28)
        assertEquals(true, adapter.read(memory)!!.look.flip)
    }

    @Test
    fun `walking left counts from the other side of the tile`() {
        walkingRight()
        put32(layout.fieldCamera + 0x10, -4)
        assertEquals(10 * 16 - 4 + 16, adapter.read(memory)!!.x)
    }

    @Test
    fun `nothing outside the overworld or when the player is hidden`() {
        walkingRight()
        put8(layout.sprites + 3 * 0x44 + 0x3E, 0x7)
        assertNull(adapter.read(memory))
        walkingRight()
        put32(layout.main + 4, 0x08000101)
        assertNull(adapter.read(memory))
    }

    @Test
    fun `covered while the start menu or a text box holds the player`() {
        walkingRight()
        assertEquals(false, adapter.read(memory)!!.covered)
        put8(layout.fieldControlsLocked, 1)
        val avatar = adapter.read(memory)!!
        // Still on the map for everyone else, just not drawn over the menu.
        assertEquals("0.9", avatar.map)
        assertEquals(true, avatar.covered)
    }

    @Test
    fun `layouts are picked by game code and version`() {
        assertEquals(Gen3Adapter.EMERALD, Gen3Adapter.layoutFor("BPEE", 0))
        assertEquals(Gen3Adapter.FIRERED_11, Gen3Adapter.layoutFor("BPRE", 1))
        assertEquals(Gen3Adapter.RUBY_11, Gen3Adapter.layoutFor("AXVE", 2))
        assertNull(Gen3Adapter.layoutFor("BPEJ", 0))
        assertNull(Gen3Adapter.layoutFor("BPEE", 1))
    }

    // Real Emerald memory in Littleroot Town (WORLD_E2E: emerald.ewram and emerald.iwram dumped
    // from a desktop emulator) with the player's own ROM.
    @Test
    fun `real Emerald memory - Littleroot Town, Route 101 to the north, place names`() {
        val romFile = System.getenv("EMERALD_ROM")?.let(::File)
        val folder = System.getenv("WORLD_E2E")?.let(::File)
        assumeTrue(romFile != null && romFile.exists() && folder != null && File(folder, "emerald.ewram").exists())
        val real = WorldAdapters.forRom("gba", romFile!!.readBytes())!!
        val memory =
            GameMemory(
                listOf(
                    GameMemory.Region(0x02000000, ByteBuffer.wrap(File(folder, "emerald.ewram").readBytes())),
                    GameMemory.Region(0x03000000, ByteBuffer.wrap(File(folder, "emerald.iwram").readBytes())),
                ),
            )
        val avatar = real.read(memory)!!
        assertEquals("0.9", avatar.map)
        assertEquals(Facing.DOWN, avatar.facing)
        // Route 101 is joined on the north side, lined up (offset 0); it is 20 tiles tall.
        assertEquals(mapOf("0.16" to MapOffset(0, -20 * 16)), real.neighbors(memory))
        assertEquals(Place("Littleroot Town", false), real.place("0.9"))
        assertEquals(Place("Route 101", false), real.place("0.16"))
    }

    // Real FireRed and LeafGreen memory just outside the player's house in Pallet Town
    // (WORLD_E2E: firered.* and leafgreen.* dumps) with the player's own ROMs (FIRERED_ROM,
    // LEAFGREEN_ROM).
    @Test
    fun `real FireRed memory - Pallet Town, Route 1 and Route 21, place names, sprite`() = palletTown("FIRERED_ROM", "firered")

    @Test
    fun `real LeafGreen memory - Pallet Town, Route 1 and Route 21, place names, sprite`() = palletTown("LEAFGREEN_ROM", "leafgreen")

    private fun palletTown(
        romVariable: String,
        dump: String,
    ) {
        val romFile = System.getenv(romVariable)?.let(::File)
        val folder = System.getenv("WORLD_E2E")?.let(::File)
        assumeTrue(romFile != null && romFile.exists() && folder != null && File(folder, "$dump.ewram").exists())
        val real = WorldAdapters.forRom("gba", romFile!!.readBytes())!!
        // FireRed and LeafGreen players share one world.
        assertEquals("kanto-frlg", real.world)
        val memory =
            GameMemory(
                listOf(
                    GameMemory.Region(0x02000000, ByteBuffer.wrap(File(folder, "$dump.ewram").readBytes())),
                    GameMemory.Region(0x03000000, ByteBuffer.wrap(File(folder, "$dump.iwram").readBytes())),
                ),
            )
        val avatar = real.read(memory)!!
        assertEquals("3.0", avatar.map)
        assertEquals(6 * 16, avatar.x)
        assertEquals(8 * 16, avatar.y)
        assertEquals(Facing.DOWN, avatar.facing)
        assertEquals(false, avatar.covered)
        // Route 1 (40 tiles tall) to the north and Route 21 to the south, both lined up.
        assertEquals(mapOf("3.19" to MapOffset(0, -40 * 16), "3.39" to MapOffset(0, 20 * 16)), real.neighbors(memory))
        assertEquals(Place("Pallet Town", false), real.place("3.0"))
        assertEquals(Place("Route 1", false), real.place("3.19"))
        assertEquals(Place("Route 21", false), real.place("3.39"))
        // The player's house is in Pallet Town, indoors.
        assertEquals(Place("Pallet Town", true), real.place("4.0"))
        // Red, drawn from the game's own graphics in every frame.
        for (frame in 0 until 18) {
            val picture = real.sprite(avatar.look.copy(frame = frame))
            assertNotNull("frame $frame", picture)
            assertEquals(16, picture!!.width)
            assertEquals(32, picture.height)
            assertTrue(picture.pixels.count { it != 0 } > 100)
        }
    }

    // Uses a real Emerald ROM when EMERALD_ROM points to one (never stored with the code).
    @Test
    fun `draws Brendan and May from the game's own graphics`() {
        val file = System.getenv("EMERALD_ROM")?.let(::File)
        assumeTrue(file != null && file.exists())
        val rom = file!!.readBytes()
        val real = WorldAdapters.forRom("gba", rom)
        assertNotNull(real)
        for (sprite in listOf(0, 89)) {
            for (frame in 0 until 18) {
                val picture = real!!.sprite(Look(sprite, frame, false))
                assertNotNull("sprite $sprite frame $frame", picture)
                assertEquals(16, picture!!.width)
                assertEquals(32, picture.height)
                assertTrue(picture.pixels.count { it != 0 } > 100)
            }
        }
        assertEquals("hoenn-e", real!!.world)
    }
}
