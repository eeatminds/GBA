package com.swordfish.libretrodroid;

import java.nio.ByteBuffer;

/** A block of the emulated console's memory, starting at [start] in the game's address space. */
public class MemoryRegion {
    public final long start;
    public final ByteBuffer data;

    public MemoryRegion(long start, ByteBuffer data) {
        this.start = start;
        this.data = data;
    }
}
