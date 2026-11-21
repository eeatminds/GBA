package com.swordfish.lemuroid.app.shared.online.world

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trade or battle right where two FireRed/LeafGreen players stand, with the game's own link code:
 * the same link-up the Cable Club attendant starts, and the same "link room" mode the Trade Center
 * and the Colosseum use, only on the map the players are on. Small scripts, written into memory
 * nothing uses (the end of work RAM), are started through the game's script slot, step by step:
 *
 * 1. Link up (what the attendant does after "save the game?"), then a cable club warp onto the
 *    tile the player stands on: the map reloads in link mode, both players on it.
 * 2. Take a "seat": the trade menu or the battle starts, as from a Trade Center chair or a
 *    Colosseum spot. After a battle, or once the players leave the trade menu, the game comes
 *    back to the map, still linked.
 * 3. One player (the link's leader) leaves the link, the game's "exit the room" key; the other
 *    game follows. Both warp back onto the tile they started on, the link closed.
 *
 * The leader's game asks its player twice to press A once everyone is linked ("When all players
 * are ready..."). Both players already agreed in the app, so that answer is given here, by making
 * the same changes the game makes when A is pressed.
 */
class Gen3InPlace(
    private val layout: Gen3Adapter.Layout,
    private val link: Gen3Adapter.InPlace,
) {
    /** One in-place trade or battle, for [WorldSession] to move on every tick. */
    fun begin(
        kind: String,
        now: Long,
    ): InPlaceSteps {
        val progress = Progress(kind, now)
        return object : InPlaceSteps {
            override fun step(
                memory: GameMemory,
                now: Long,
            ): String? = this@Gen3InPlace.step(memory, progress, now)

            override fun running(memory: GameMemory): Boolean = progress.started && this@Gen3InPlace.running(memory)
        }
    }

    // Where a running in-place link is; only [step] changes it.
    class Progress(val kind: String, val startedAt: Long) {
        @Volatile var started = false
        @Volatile var startedScriptAt = 0L
        @Volatile var seated = false
        @Volatile var leaving = false
        @Volatile var cancelled = false
        @Volatile var idleSince = 0L
    }

    /**
     * Run on the emulator thread (between frames) while an in-place link goes: writes what the
     * game needs next. Returns what it did, for the link report, or null.
     */
    fun step(
        memory: GameMemory,
        progress: Progress,
        now: Long,
    ): String? {
        val cb1 = (memory.u32(layout.main) ?: return null) and THUMB.inv()
        val cb2 = (memory.u32(layout.main + 4) ?: return null) and THUMB.inv()
        val idle = memory.u8(layout.fieldControlsLocked) == 0 && memory.u8(link.scriptStatus) == CONTEXT_SHUTDOWN
        val onMap = cb2 == layout.cb2Overworld
        val normalField = onMap && cb1 == link.cb1Overworld
        val linkField = onMap && cb1 == link.cb1Link
        val battle = progress.kind == Talk.BATTLE

        if (!progress.started) {
            // Wait for the player to be free on the map (no menu, text or script), but not past
            // the point where the app has given up on this link.
            if (!(normalField && idle) || now - progress.startedAt > START_DEADLINE_MS) return null
            val start = startScript(memory, battle) ?: return "can't read where the player stands"
            run(memory, start)
            progress.started = true
            progress.startedScriptAt = now
            return "link-up script started"
        }

        if (!progress.seated && confirmLinkupForLeader(memory)) return "everyone is linked: the leader's game goes on"

        // Nobody came: stop waiting, as when the player presses B in the game.
        if (!progress.seated && !progress.cancelled && now - progress.startedScriptAt > LINK_UP_TIMEOUT_MS &&
            memory.u8(link.receivedRemoteLinkPlayers) == 0 && cancelLinkup(memory)
        ) {
            progress.cancelled = true
            return "nobody linked up in ${LINK_UP_TIMEOUT_MS / 1000}s: link-up cancelled"
        }

        // The other game left the link: leave too, without the game's "Terminating link...
        // you will be escorted out" message (written for the link rooms; it waits for A).
        val script = memory.u32(link.scriptContext + CONTEXT_SCRIPT_PTR) ?: 0
        if (memory.u8(link.scriptStatus) != CONTEXT_SHUTDOWN && script in link.terminateLinkScript until link.terminateLinkScript + 12) {
            run(memory, exitScript())
            progress.leaving = true
            return "the other game left the link: leaving too"
        }

        if (!linkField || !idle) {
            progress.idleSince = 0
            return null
        }
        if (!progress.seated) {
            // The seat is our place on the link (the link's own id for us).
            run(memory, seatScript(battle, seat = ((memory.u32(link.linkStatus) ?: 0) and 3).toInt()))
            progress.seated = true
            return "linked on this map: taking a seat for the ${progress.kind}"
        }
        // Back on the map, still linked: the trade or battle is over (or never started).
        if (progress.leaving) return null
        if (progress.idleSince == 0L) progress.idleSince = now
        val leader = ((memory.u32(link.linkStatus) ?: 0) and LINK_STAT_MASTER) != 0L
        // One game leaves, the other follows: both at once, the games miss one of the two.
        val wait = if (leader) LEADER_LEAVES_AFTER_MS else FOLLOWER_LEAVES_AFTER_MS
        if (now - progress.idleSince < wait) return null
        run(memory, exitScript())
        progress.leaving = true
        return if (leader) "back on the map: leaving the link" else "the other game hasn't left the link: leaving"
    }

    /** Whether the game is anywhere in the in-place link (anything but the normal, free map). */
    fun running(memory: GameMemory): Boolean {
        val cb1 = (memory.u32(layout.main) ?: return false) and THUMB.inv()
        val cb2 = (memory.u32(layout.main + 4) ?: return false) and THUMB.inv()
        return memory.u8(link.scriptStatus) != CONTEXT_SHUTDOWN || cb1 != link.cb1Overworld || cb2 != layout.cb2Overworld
    }

    /** Whether the games are linked (players known to each other, or the map in link mode). */
    fun linked(memory: GameMemory): Boolean =
        memory.u8(link.receivedRemoteLinkPlayers) == 1 ||
            ((memory.u32(layout.main) ?: 0) and THUMB.inv()) == link.cb1Link

    fun inLinkMode(memory: GameMemory): Boolean = ((memory.u32(layout.main) ?: 0) and THUMB.inv()) == link.cb1Link

    fun scriptRunning(memory: GameMemory): Boolean = memory.u8(link.scriptStatus) != CONTEXT_SHUTDOWN

    fun debug(memory: GameMemory): String {
        fun hex(address: Long) = memory.u32(address)?.let { "%x".format(it) } ?: "--"
        return "cb1=${hex(layout.main)} cb2=${hex(layout.main + 4)} script=${memory.u8(link.scriptStatus)}@${hex(link.scriptContext + CONTEXT_SCRIPT_PTR)} " +
            "lock=${memory.u8(layout.fieldControlsLocked)} link=${hex(link.linkStatus)} remote=${memory.u8(link.receivedRemoteLinkPlayers)} " +
            "tasks=${linkupTask(memory)?.let { hex(it) } ?: "-"}"
    }

    // The game's link-up task, if it runs: its address in the task list.
    private fun linkupTask(memory: GameMemory): Long? =
        (0 until TASK_COUNT).map { link.tasks + it * TASK_SIZE }.firstOrNull { task ->
            memory.u8(task + TASK_ACTIVE) == 1 && ((memory.u32(task) ?: 0) and THUMB.inv()) in link.linkupTasks
        }

    // The leader's "press A when everyone is ready" and "start the link? A": what the game does on
    // A (SaveLinkPlayers, CheckShouldAdvanceLinkState), then on to the data exchange.
    private fun confirmLinkupForLeader(memory: GameMemory): Boolean {
        val status = memory.u32(link.linkStatus) ?: return false
        val players = ((status shr 2) and 7).toInt()
        if (status and LINK_STAT_MASTER == 0L || players < 2) return false
        val task =
            (0 until TASK_COUNT).map { link.tasks + it * TASK_SIZE }.firstOrNull { task ->
                memory.u8(task + TASK_ACTIVE) == 1 && ((memory.u32(task) ?: 0) and THUMB.inv()) in link.leaderWaits
            } ?: return false
        val linkPlayers = memory.read(link.linkPlayers, link.linkPlayersSize) ?: return false
        return memory.write(link.suppressLinkErrorMessage, byteArrayOf(1)) &&
            memory.write(link.savedLinkPlayerCount, byteArrayOf(players.toByte())) &&
            memory.write(link.savedMultiplayerId, byteArrayOf((status and 3).toByte())) &&
            memory.write(link.savedLinkPlayers, linkPlayers) &&
            memory.write(link.shouldAdvanceLinkState, byteArrayOf(1)) &&
            memory.write(task, le32(link.linkupConfirm or THUMB))
    }

    // As B in the link-up: the task ends with "link-up failed" and our script closes the link.
    private fun cancelLinkup(memory: GameMemory): Boolean {
        val task = linkupTask(memory) ?: return false
        return memory.write(task, le32(link.linkupFailed or THUMB))
    }

    // ScriptContext_SetupScript: a fresh global script context on our script, the field held.
    private fun run(
        memory: GameMemory,
        script: ByteArray,
    ) {
        val context =
            ByteBuffer.allocate(CONTEXT_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(0, 0) // call depth
                put(1, 1) // running bytecode
                putInt(CONTEXT_SCRIPT_PTR.toInt(), link.scriptBuffer.toInt())
                putInt(CONTEXT_CMD_TABLE, link.scriptCmdTable.toInt())
                putInt(CONTEXT_CMD_TABLE + 4, link.scriptCmdTableEnd.toInt())
            }.array()
        // The script must stay inside work RAM (hacks put it in its last 256 bytes).
        require(link.scriptBuffer + script.size <= EWRAM_END) { "script of ${script.size} bytes doesn't fit" }
        memory.write(link.scriptBuffer, script)
        memory.write(link.scriptContext, context)
        memory.write(layout.fieldControlsLocked, byteArrayOf(1))
        memory.write(link.scriptStatus, byteArrayOf(CONTEXT_RUNNING))
    }

    // 1: as the Direct Corner attendant (in FireRed/LeafGreen the quest log cut and the help system
    // off; where there is one, the wireless adapter check that also puts the cable link's interrupt
    // handlers in place; the link-up), then the cable club warp onto this tile. A battle also heals the party and keeps it and the bag to give back afterwards, as the
    // Colosseum does. If the link-up fails: close the link, free the player.
    private fun startScript(
        memory: GameMemory,
        battle: Boolean,
    ): ByteArray? {
        val saveBlock = if (layout.saveBlock1IsPointer) memory.u32(layout.saveBlock1) ?: return null else layout.saveBlock1
        val x = memory.s16(saveBlock) ?: return null
        val y = memory.s16(saveBlock + 2) ?: return null
        val group = memory.u8(saveBlock + 4) ?: return null
        val number = memory.u8(saveBlock + 5) ?: return null
        val here = byteArrayOf(group.toByte(), number.toByte(), NO_WARP) + le16(x) + le16(y)
        val script = Script()
        script.op(LOCKALL)
        link.specials.questLogCutRecording?.let { script.special(it) }
        link.specials.isWirelessAdapterConnected?.let { script.op(SPECIALVAR).u16(VAR_RESULT).u16(it) }
        link.specials.helpSystemDisable?.let { script.special(it) }
        if (link.specials.textColor) script.op(TEXTCOLOR).u8(TEXT_COLOR_NEUTRAL)
        script.op(SETVAR).u16(VAR_0x8004).u16(if (battle) USING_SINGLE_BATTLE else USING_TRADE_CENTER)
        script.special(if (battle) link.specials.tryBattleLinkup else link.specials.tryTradeLinkup).op(WAITSTATE)
        script.op(COMPARE_VAR_TO_VALUE).u16(VAR_RESULT).u16(LINKUP_SUCCESS)
        val failJump = script.size + 2
        script.op(GOTO_IF).u8(COND_NE).u32(0)
        if (battle) {
            script.special(link.specials.healPlayerParty).special(link.specials.savePlayerParty).special(link.specials.loadPlayerBag)
        }
        script.op(SETDYNAMICWARP).bytes(here).op(SETWARP).bytes(here)
        script.special(link.specials.doCableClubWarp).op(WAITSTATE).op(END)
        val fail = script.size
        script.special(link.specials.closeLink)
        link.specials.helpSystemEnable?.let { script.special(it) }
        script.op(RELEASEALL).op(END)
        return script.patchU32(failJump, link.scriptBuffer + fail).build()
    }

    // 2: what a Trade Center chair or a Colosseum spot runs; the seat is our place on the link.
    private fun seatScript(
        battle: Boolean,
        seat: Int,
    ): ByteArray =
        Script().op(SETVAR).u16(VAR_0x8005).u16(seat)
            .apply { if (link.specials.textColor) op(TEXTCOLOR).u8(TEXT_COLOR_NEUTRAL) }
            .special(if (battle) link.specials.enterColosseumPlayerSpot else link.specials.enterTradeSeat)
            .op(WAITSTATE).op(END).build()

    // 3: the link room's "exit" (the game then warps each player back to where we started).
    private fun exitScript(): ByteArray = Script().op(CLOSEMESSAGE).special(link.specials.exitLinkRoom).op(END).build()

    private class Script {
        private val out = java.io.ByteArrayOutputStream()
        private var patches = mutableListOf<Pair<Int, Long>>()
        val size: Int get() = out.size()

        fun op(code: Int) = u8(code)

        fun u8(value: Int) = apply { out.write(value) }

        fun u16(value: Int) = apply { out.write(le16(value)) }

        fun u32(value: Long) = apply { out.write(le32(value)) }

        fun bytes(value: ByteArray) = apply { out.write(value) }

        fun special(number: Int) = op(SPECIAL).u16(number)

        fun patchU32(
            at: Int,
            value: Long,
        ) = apply { patches.add(at to value) }

        fun build(): ByteArray {
            val bytes = out.toByteArray()
            for ((at, value) in patches) le32(value).copyInto(bytes, at)
            return bytes
        }
    }

    /**
     * The game's numbers for the specials the scripts call (each pair of games shares them), and the
     * steps only some games have: FireRed/LeafGreen's quest log, help system and text colours, and
     * the wireless adapter check (not in Ruby/Sapphire).
     */
    data class Specials(
        val healPlayerParty: Int,
        val doCableClubWarp: Int,
        val exitLinkRoom: Int,
        val tryBattleLinkup: Int,
        val tryTradeLinkup: Int,
        val closeLink: Int,
        val enterColosseumPlayerSpot: Int,
        val enterTradeSeat: Int,
        val savePlayerParty: Int,
        val loadPlayerBag: Int,
        val isWirelessAdapterConnected: Int?,
        val questLogCutRecording: Int? = null,
        val helpSystemDisable: Int? = null,
        val helpSystemEnable: Int? = null,
        val textColor: Boolean = false,
    )

    companion object {
        private const val EWRAM_END = 0x02040000L

        private const val THUMB = 1L

        // The game's script commands (the same numbers in all five games).
        private const val END = 0x02
        private const val GOTO_IF = 0x06
        private const val SETVAR = 0x16
        private const val COMPARE_VAR_TO_VALUE = 0x21
        private const val SPECIAL = 0x25
        private const val SPECIALVAR = 0x26
        private const val WAITSTATE = 0x27
        private const val SETWARP = 0x3E
        private const val SETDYNAMICWARP = 0x3F
        private const val CLOSEMESSAGE = 0x68
        private const val LOCKALL = 0x69
        private const val RELEASEALL = 0x6B
        private const val TEXTCOLOR = 0xC7
        private const val COND_NE = 5
        private const val TEXT_COLOR_NEUTRAL = 3
        private const val NO_WARP: Byte = -1

        private const val VAR_0x8004 = 0x8004
        private const val VAR_0x8005 = 0x8005
        private const val VAR_RESULT = 0x800D
        private const val USING_SINGLE_BATTLE = 1
        private const val USING_TRADE_CENTER = 3
        private const val LINKUP_SUCCESS = 1

        private const val CONTEXT_RUNNING: Byte = 0
        private const val CONTEXT_SHUTDOWN = 2
        private const val CONTEXT_SIZE = 0x74
        private const val CONTEXT_SCRIPT_PTR = 0x08L
        private const val CONTEXT_CMD_TABLE = 0x5C

        private const val TASK_COUNT = 16
        private const val TASK_SIZE = 40L
        private const val TASK_ACTIVE = 4L
        private const val LINK_STAT_MASTER = 0x20L

        const val LINK_UP_TIMEOUT_MS = 30_000L
        private const val START_DEADLINE_MS = 15_000L
        private const val LEADER_LEAVES_AFTER_MS = 500L
        private const val FOLLOWER_LEAVES_AFTER_MS = 6_000L

        private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())

        private fun le32(value: Long) =
            byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

        val FRLG_SPECIALS =
            Specials(
                healPlayerParty = 0,
                doCableClubWarp = 2,
                exitLinkRoom = 5,
                tryBattleLinkup = 28,
                tryTradeLinkup = 29,
                closeLink = 31,
                enterColosseumPlayerSpot = 32,
                enterTradeSeat = 33,
                savePlayerParty = 39,
                loadPlayerBag = 331,
                isWirelessAdapterConnected = 362,
                questLogCutRecording = 392,
                helpSystemDisable = 408,
                helpSystemEnable = 409,
                textColor = true,
            )

        val EMERALD_SPECIALS =
            Specials(
                healPlayerParty = 0,
                doCableClubWarp = 2,
                exitLinkRoom = 5,
                tryBattleLinkup = 28,
                tryTradeLinkup = 29,
                closeLink = 32,
                enterColosseumPlayerSpot = 33,
                enterTradeSeat = 34,
                savePlayerParty = 40,
                loadPlayerBag = 333,
                isWirelessAdapterConnected = 415,
            )

        // Ruby/Sapphire (pokeruby's names: sub_808347C, sub_80834E4, sub_8083B90, sub_8083B5C, sub_80839D0).
        val RUBY_SAPPHIRE_SPECIALS =
            Specials(
                healPlayerParty = 0,
                doCableClubWarp = 2,
                exitLinkRoom = 5,
                tryBattleLinkup = 28,
                tryTradeLinkup = 29,
                closeLink = 31,
                enterColosseumPlayerSpot = 32,
                enterTradeSeat = 33,
                savePlayerParty = 39,
                loadPlayerBag = 331,
                isWirelessAdapterConnected = null,
            )
    }
}
