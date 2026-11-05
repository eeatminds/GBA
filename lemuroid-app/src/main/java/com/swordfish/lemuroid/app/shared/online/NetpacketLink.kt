package com.swordfish.lemuroid.app.shared.online

import com.swordfish.libretrodroid.LibretroDroid

/**
 * A GBA link cable through gpSP's netpacket interface, inside a trade & battle room.
 *
 * The core numbers players by their place on the cable (slot 0 leads the link), while the
 * room numbers them by player id. This class starts and stops the core's link as our link
 * group changes, and translates packets between slots and room ids.
 */
class NetpacketLink(private val core: Core) {
    /** The parts of LibretroDroid's netpacket bridge this uses. */
    interface Core {
        fun start(slot: Int)

        fun stop()

        fun peerConnected(slot: Int)

        fun peerDisconnected(slot: Int)

        fun push(
            fromSlot: Int,
            data: ByteArray,
        )

        fun wakeTakers()
    }

    object LibretroCore : Core {
        override fun start(slot: Int) = LibretroDroid.netpacketStart(slot)

        override fun stop() = LibretroDroid.netpacketStop()

        override fun peerConnected(slot: Int) = LibretroDroid.netpacketPeerConnected(slot)

        override fun peerDisconnected(slot: Int) = LibretroDroid.netpacketPeerDisconnected(slot)

        override fun push(
            fromSlot: Int,
            data: ByteArray,
        ) = LibretroDroid.netpacketPushIncoming(fromSlot, data)

        override fun wakeTakers() = LibretroDroid.netpacketWakeTakers()
    }

    private var mySlot: Int? = null
    private var leaderId: Int? = null
    private var connectedSlots: Set<Int> = emptySet()

    @Volatile private var slotByPlayer: Map<Int, Int> = emptyMap()

    @Volatile private var playerBySlot: Map<Int, Int> = emptyMap()

    /** Our link group changed; null (or a group of one) when we are not linked. */
    @Synchronized
    fun groupChanged(
        myId: Int,
        group: List<OnlineSession.LinkMember>?,
    ) {
        val members = group?.takeIf { it.size >= 2 }
        val newSlot = members?.firstOrNull { it.id == myId }?.slot
        val newLeader = members?.firstOrNull { it.slot == 0 }?.id

        // A new place on the cable, or a new leader, means a new link session for the core.
        val restart = newSlot != mySlot || newLeader != leaderId
        if (restart && mySlot != null) {
            core.stop()
            connectedSlots = emptySet()
        }
        slotByPlayer = members?.associate { it.id to it.slot } ?: emptyMap()
        playerBySlot = members?.associate { it.slot to it.id } ?: emptyMap()
        if (restart && newSlot != null) core.start(newSlot)
        mySlot = newSlot
        leaderId = newLeader

        // The leader is told when others plug in or out.
        if (newSlot == 0 && members != null) {
            val others = members.filter { it.id != myId }.map { it.slot }.toSet()
            (others - connectedSlots).sorted().forEach { core.peerConnected(it) }
            (connectedSlots - others).sorted().forEach { core.peerDisconnected(it) }
            connectedSlots = others
        } else {
            connectedSlots = emptySet()
        }
    }

    @Synchronized
    fun stop() {
        if (mySlot != null) core.stop()
        mySlot = null
        leaderId = null
        connectedSlots = emptySet()
        slotByPlayer = emptyMap()
        playerBySlot = emptyMap()
        core.wakeTakers()
    }

    /** A packet from room player [fromId]; dropped unless they are on our link. */
    fun push(
        fromId: Int,
        data: ByteArray,
    ) {
        val slot = slotByPlayer[fromId] ?: return
        core.push(slot, data)
    }

    /** The room player (or broadcast) for a packet the core addressed to [slot]. */
    fun roomTarget(slot: Int): Int? = if (slot == BROADCAST) slot else playerBySlot[slot]

    companion object {
        const val BROADCAST = 0xFFFF
    }
}
