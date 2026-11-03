package com.swordfish.lemuroid.app.shared.online.world

import com.swordfish.libretrodroid.LibretroDroid
import java.nio.ByteBuffer

/**
 * Reads the running game's memory at the addresses the game itself uses (for example
 * 0x02000000 for GBA work RAM). Reads happen while the game runs, so a value can be from
 * the middle of a frame; callers treat odd values as "not now".
 */
open class GameMemory(private val regions: List<Region>) {
    class Region(val start: Long, val buffer: ByteBuffer) {
        val end: Long = start + buffer.capacity()
    }

    private fun locate(
        address: Long,
        length: Int,
    ): Pair<ByteBuffer, Int>? {
        for (region in regions) {
            if (address >= region.start && address + length <= region.end) {
                return region.buffer to (address - region.start).toInt()
            }
        }
        return null
    }

    fun u8(address: Long): Int? {
        val (buffer, offset) = locate(address, 1) ?: return null
        return buffer.get(offset).toInt() and 0xFF
    }

    fun s8(address: Long): Int? = u8(address)?.let { if (it >= 0x80) it - 0x100 else it }

    fun u16(address: Long): Int? {
        val (buffer, offset) = locate(address, 2) ?: return null
        return (buffer.get(offset).toInt() and 0xFF) or ((buffer.get(offset + 1).toInt() and 0xFF) shl 8)
    }

    fun s16(address: Long): Int? = u16(address)?.let { if (it >= 0x8000) it - 0x10000 else it }

    fun u32(address: Long): Long? {
        val low = u16(address) ?: return null
        val high = u16(address + 2) ?: return null
        return low.toLong() or (high.toLong() shl 16)
    }

    fun s32(address: Long): Int? = u32(address)?.toInt()

    /** Writes [bytes] at [address]. Only call it between frames (on the emulation thread). */
    fun write(
        address: Long,
        bytes: ByteArray,
    ): Boolean {
        val (buffer, offset) = locate(address, bytes.size) ?: return false
        bytes.forEachIndexed { index, byte -> buffer.put(offset + index, byte) }
        return true
    }

    companion object {
        /** Base address used for the console's whole work RAM block (all banks, in order). */
        const val SYSTEM_RAM_BASE = 0x1_0000_0000L

        /**
         * The memory of the game that's running now. Besides the core's memory map, the whole
         * work RAM is also available at [SYSTEM_RAM_BASE] (for banked Game Boy Color RAM).
         */
        fun ofRunningGame(): GameMemory? {
            val regions =
                LibretroDroid.getMemoryRegions().orEmpty().mapNotNull { region ->
                    region.data?.let { Region(region.start, it) }
                }.toMutableList()
            LibretroDroid.getMemoryData(LibretroDroid.MEMORY_SYSTEM_RAM)?.let { regions += Region(SYSTEM_RAM_BASE, it) }
            return if (regions.isEmpty()) null else GameMemory(regions)
        }
    }
}
