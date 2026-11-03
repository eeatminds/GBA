package com.swordfish.lemuroid.app.shared.online.world

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Shares where our character is with the room, and keeps where everyone else is.
 *
 * About 15 times a second it reads our character from the game's memory and, when it moved
 * (or once a second anyway), sends it as a small "pos" message. Other players' messages come
 * in through [onPresence]; the overlay draws the ones on our map or a map joined to it, and the
 * Players panel says where everyone is. Games that can't be shown use [NoWorld], which only
 * tells the others so.
 */
class WorldSession(
    val adapter: WorldAdapter,
    private val memory: () -> GameMemory?,
    private val send: (JSONObject) -> Unit,
    private val scope: CoroutineScope,
    // Runs a block on the emulation thread between frames (for writing game memory safely).
    private val onEmulator: (() -> Unit) -> Unit = { it() },
) {
    /**
     * Another player's recent positions, played back a little behind real time ([PLAYBACK_DELAY_MS])
     * so their walk stays smooth when messages arrive unevenly.
     *
     * Messages carry no time, so each one gets a playback time: the sender reads its game at a
     * steady pace, so while they move, one more interval after the previous message (never later
     * than when it arrived, nor far before); after a pause, when it arrived.
     */
    class Remote private constructor(
        private val samples: List<Sample>,
        private val interval: Float,
    ) {
        class Sample(val avatar: Avatar, val time: Long)

        val current: Avatar get() = samples.last().avatar

        /** Where to draw them at playback time [time] (milliseconds, [SystemClock.uptimeMillis] based). */
        fun at(time: Long): Avatar {
            val next = samples.indexOfFirst { it.time > time }
            if (next == -1) return current
            if (next == 0) return samples[0].avatar
            val a = samples[next - 1]
            val b = samples[next]
            // Big jumps (warps, a lost message) and map changes snap instead of sliding.
            if (a.avatar.map != b.avatar.map || abs(b.avatar.x - a.avatar.x) > 48 || abs(b.avatar.y - a.avatar.y) > 48) return a.avatar
            val progress = (time - a.time).toFloat() / (b.time - a.time).coerceAtLeast(1)
            return a.avatar.copy(
                x = a.avatar.x + ((b.avatar.x - a.avatar.x) * progress).roundToInt(),
                y = a.avatar.y + ((b.avatar.y - a.avatar.y) * progress).roundToInt(),
            )
        }

        /** These positions plus [avatar], which arrived at [now]. */
        fun with(
            avatar: Avatar,
            now: Long,
        ): Remote {
            val last = samples.last()
            val gap = now - last.time
            val moving = gap < PAUSE_MS
            // The sender's pace, learned from messages that came while they kept moving.
            val pace = if (moving && gap > 0) (interval * 0.9f + gap.coerceIn(TICK_MS / 2, TICK_MS * 2) * 0.1f) else interval
            val time = if (moving) (last.time + pace.roundToLong()).coerceIn(now - MAX_LAG_MS, now) else now
            val kept = samples.filter { it.time >= now - HISTORY_MS }.takeLast(MAX_SAMPLES - 2)
            // After a pause they stood still until one step before this: don't slide across the pause.
            val hold = if (!moving && now - TICK_MS > last.time) listOf(Sample(last.avatar, now - TICK_MS)) else emptyList()
            return Remote(kept + hold + Sample(avatar, time.coerceAtLeast(last.time + 1)), pace)
        }

        companion object {
            fun of(
                avatar: Avatar,
                now: Long,
            ) = Remote(listOf(Sample(avatar, now)), TICK_MS.toFloat())

            private const val PAUSE_MS = TICK_MS * 3
            private const val MAX_LAG_MS = 100L
            private const val HISTORY_MS = 1_500L
            private const val MAX_SAMPLES = 24
        }
    }

    /** What we last heard from a player: their game's world, and their map (null: not walking around). */
    data class Presence(val world: String, val map: String?)

    data class View(
        val local: Avatar? = null,
        val others: Map<Int, Remote> = emptyMap(),
        // Maps joined to ours, and where each sits on it.
        val neighbors: Map<String, MapOffset> = emptyMap(),
        val presences: Map<Int, Presence> = emptyMap(),
    ) {
        /** [avatar] in our map's coordinates, or null when it is on a map that isn't ours or joined to it. */
        fun onOurMap(avatar: Avatar): Avatar? = local?.let { onMap(avatar, it) }

        /** The same for [ours] read just now ([neighbors] belong to [local]'s map). */
        fun onMap(
            avatar: Avatar,
            ours: Avatar,
        ): Avatar? {
            if (avatar.map == ours.map) return avatar
            if (ours.map != local?.map) return null
            val offset = neighbors[avatar.map] ?: return null
            return avatar.copy(map = ours.map, x = avatar.x + offset.dx, y = avatar.y + offset.dy)
        }
    }

    /** Where a player is, for the Players panel. */
    sealed interface Spot {
        /** On our map ([place]: its name). */
        data class Here(val place: String?) : Spot

        /** On a map joined to ours. */
        data class Nearby(val place: String?) : Spot

        data class Elsewhere(val place: String?) : Spot

        /** In a menu, a battle or between maps. */
        data object Busy : Spot

        /** Playing another game: they trade and battle with us, but aren't drawn on our map. */
        data class OtherGame(val game: String?) : Spot

        /** Their game can't be shown on maps. */
        data object NotShown : Spot

        data object Unknown : Spot
    }

    private val remotes = ConcurrentHashMap<Int, Remote>()
    private val presences = ConcurrentHashMap<Int, Presence>()

    // An in-place trade or battle we started: when, and whether its script has been seen running.
    private class InPlace(val startedAt: Long, var seenRunning: Boolean = false)

    @Volatile private var inPlace: InPlace? = null
    @Volatile private var gameMemory: GameMemory? = null

    /** Our visit to the host's world, for guests in games that can visit. */
    @Volatile var visit: WorldVisit? = null

    /** Whether this game can trade and battle right where the players stand. */
    val canLinkInPlace: Boolean get() = adapter.inPlaceLink(Talk.TRADE) != null
    private val mutableView = MutableStateFlow(View())
    val view: StateFlow<View> = mutableView.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch(Dispatchers.Default) {
                var lastSent: Avatar? = null
                var lastSentAt = 0L
                var sentHidden = false
                var neighborsOf: String? = null
                var neighbors = emptyMap<String, MapOffset>()
                while (isActive) {
                    if (gameMemory == null) gameMemory = memory()
                    val gameMemory = gameMemory
                    val local = gameMemory?.let { runCatching { adapter.read(it) }.getOrNull() }
                    if (gameMemory != null) watchInPlace(gameMemory)
                    // The maps joined to ours change only with our map.
                    if (local != null && local.map != neighborsOf) {
                        neighbors = gameMemory?.let { runCatching { adapter.neighbors(it) }.getOrNull() }.orEmpty()
                        neighborsOf = local.map
                    }
                    val now = SystemClock.uptimeMillis()
                    val shared = local?.copy(screenX = 0, screenY = 0, covered = false)
                    if (shared != null && (shared != lastSent || now - lastSentAt >= HEARTBEAT_MS)) {
                        send(message(shared))
                        lastSent = shared
                        lastSentAt = now
                        sentHidden = false
                    } else if (shared == null && (!sentHidden || now - lastSentAt >= HEARTBEAT_MS)) {
                        send(JSONObject().put("t", "pos").put("w", adapter.world))
                        lastSent = null
                        lastSentAt = now
                        sentHidden = true
                    }
                    mutableView.value = View(local, HashMap(remotes), neighbors, HashMap(presences))
                    delay(TICK_MS)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        remotes.clear()
        presences.clear()
        mutableView.value = View()
    }

    /**
     * Starts the game's own link trade or battle ([Talk.TRADE] / [Talk.BATTLE]) right here. The
     * other player starts theirs at the same time; the game waits a few seconds for them.
     * Returns false when this game can't.
     */
    fun startInPlace(kind: String): Boolean {
        val memory = gameMemory ?: memory() ?: return false
        val patch = adapter.inPlaceLink(kind) ?: return false
        onEmulator { patch.applyTo(memory) }
        inPlace = InPlace(SystemClock.uptimeMillis())
        return true
    }

    // Once the session ends (back on the map), close the link the way the game does when leaving
    // its link rooms. A script that never got to run (the game was busy) is given up.
    private fun watchInPlace(memory: GameMemory) {
        val session = inPlace ?: return
        val running = runCatching { adapter.inPlaceLinkRunning(memory) }.getOrDefault(false)
        when {
            running -> session.seenRunning = true
            session.seenRunning -> {
                inPlace = null
                adapter.inPlaceLinkCleanup()?.let { cleanup -> onEmulator { cleanup.applyTo(memory) } }
            }
            SystemClock.uptimeMillis() - session.startedAt > IN_PLACE_START_TIMEOUT_MS -> {
                inPlace = null
                adapter.inPlaceLinkCleanup()?.let { cleanup -> onEmulator { cleanup.applyTo(memory) } }
            }
        }
    }

    /**
     * The player standing right in front of us (on the tile we face), if any: pressing A then
     * talks to them.
     */
    fun playerInFront(): Int? {
        val view = view.value
        val local = view.local ?: return null
        val facing = local.facing ?: return null
        if (local.covered || local.x % 16 != 0 || local.y % 16 != 0) return null
        val targetX = local.x + facing.dx * 16
        val targetY = local.y + facing.dy * 16
        return view.others.entries.firstOrNull { (_, remote) ->
            val placed = view.onOurMap(remote.current) ?: return@firstOrNull false
            kotlin.math.abs(placed.x - targetX) < 8 && kotlin.math.abs(placed.y - targetY) < 8
        }?.key
    }

    /** Our character at this very moment (the overlay reads it every frame), or null. */
    fun readLocal(): Avatar? = gameMemory?.let { runCatching { adapter.read(it) }.getOrNull() }

    /** Where player [id] is, as we know it. */
    fun spotOf(id: Int): Spot {
        val view = view.value
        val presence = view.presences[id] ?: return Spot.Unknown
        return when {
            presence.world == Worlds.NONE -> Spot.NotShown
            presence.world != adapter.world -> Spot.OtherGame(Worlds.gameName(presence.world))
            presence.map == null -> Spot.Busy
            view.local?.map == presence.map -> Spot.Here(placeName(presence.map))
            presence.map in view.neighbors -> Spot.Nearby(placeName(presence.map))
            else -> Spot.Elsewhere(placeName(presence.map))
        }
    }

    /** Where we are ourselves. */
    fun ourPlace(): String? = view.value.local?.let { placeName(it.map) }

    private fun placeName(map: String): String? =
        runCatching { adapter.place(map) }.getOrNull()?.let { if (it.indoors) "${it.name} (indoors)" else it.name }

    /** A "pos" message from player [from]. */
    fun onPresence(
        from: Int,
        message: JSONObject,
    ) {
        val world = message.optString("w")
        if (world.isNotEmpty()) presences[from] = Presence(world, message.optString("m").takeIf { it.isNotEmpty() })
        val avatar = parse(message)
        if (avatar == null) {
            remotes.remove(from)
            return
        }
        val now = SystemClock.uptimeMillis()
        remotes[from] = remotes[from]?.with(avatar, now) ?: Remote.of(avatar, now)
    }

    fun onPlayerLeft(id: Int) {
        remotes.remove(id)
        presences.remove(id)
    }

    private fun message(avatar: Avatar): JSONObject =
        JSONObject()
            .put("t", "pos")
            .put("w", adapter.world)
            .put("m", avatar.map)
            .put("x", avatar.x)
            .put("y", avatar.y)
            .put("s", avatar.look.sprite)
            .put("f", avatar.look.frame)
            .put("h", if (avatar.look.flip) 1 else 0)

    // Players in another world (another game) aren't drawn in ours.
    private fun parse(message: JSONObject): Avatar? {
        if (message.optString("w") != adapter.world) return null
        val map = message.optString("m").takeIf { it.isNotEmpty() } ?: return null
        return Avatar(
            map = map,
            x = message.optInt("x"),
            y = message.optInt("y"),
            look = Look(message.optInt("s"), message.optInt("f"), message.optInt("h") == 1),
        )
    }

    companion object {
        const val TICK_MS = 66L

        /** Other players are drawn this far behind real time, for smooth playback. */
        const val PLAYBACK_DELAY_MS = 150L

        // A queued script runs as soon as the player isn't busy; give up if that takes this long.
        private const val IN_PLACE_START_TIMEOUT_MS = 20_000L
        private const val HEARTBEAT_MS = 1000L
    }
}

/** For games we can't show on maps: never on a map, so the others just learn that. */
object NoWorld : WorldAdapter {
    override val world: String = Worlds.NONE
    override val screenWidth: Int = 240
    override val screenHeight: Int = 160

    override fun read(memory: GameMemory): Avatar? = null

    override fun sprite(look: Look): SpriteImage? = null
}
