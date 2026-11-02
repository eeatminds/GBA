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

/**
 * Shares where our character is with the room, and keeps where everyone else is.
 *
 * About 15 times a second it reads our character from the game's memory and, when it moved
 * (or once a second anyway), sends it as a small "pos" message. Other players' messages come
 * in through [onPresence]; the overlay draws them.
 */
class WorldSession(
    val adapter: WorldAdapter,
    private val memory: () -> GameMemory?,
    private val send: (JSONObject) -> Unit,
    private val scope: CoroutineScope,
) {
    /** Another player's last two positions, so the overlay can glide between them. */
    data class Remote(val previous: Avatar, val current: Avatar, val receivedAt: Long) {
        /** Where to draw them at [now] (milliseconds, [SystemClock.uptimeMillis]). */
        fun at(now: Long): Avatar {
            if (previous.map != current.map) return current
            val progress = ((now - receivedAt).toFloat() / TICK_MS).coerceIn(0f, 1f)
            // Big jumps (warps, a lost message) snap instead of sliding across the map.
            if (kotlin.math.abs(current.x - previous.x) > 48 || kotlin.math.abs(current.y - previous.y) > 48) return current
            return current.copy(
                x = previous.x + ((current.x - previous.x) * progress).toInt(),
                y = previous.y + ((current.y - previous.y) * progress).toInt(),
            )
        }
    }

    data class View(val local: Avatar? = null, val others: Map<Int, Remote> = emptyMap())

    private val remotes = ConcurrentHashMap<Int, Remote>()
    private val mutableView = MutableStateFlow(View())
    val view: StateFlow<View> = mutableView.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job =
            scope.launch(Dispatchers.Default) {
                var gameMemory: GameMemory? = null
                var lastSent: Avatar? = null
                var lastSentAt = 0L
                var sentHidden = false
                while (isActive) {
                    if (gameMemory == null) gameMemory = memory()
                    val local = gameMemory?.let { runCatching { adapter.read(it) }.getOrNull() }
                    val now = SystemClock.uptimeMillis()
                    val shared = local?.copy(screenX = 0, screenY = 0)
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
                    mutableView.value = View(local, HashMap(remotes))
                    delay(TICK_MS)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        remotes.clear()
        mutableView.value = View()
    }

    /** A "pos" message from player [from]. */
    fun onPresence(
        from: Int,
        message: JSONObject,
    ) {
        val avatar = parse(message)
        if (avatar == null) {
            remotes.remove(from)
            return
        }
        val now = SystemClock.uptimeMillis()
        val previous = remotes[from]?.let { it.at(now) } ?: avatar
        remotes[from] = Remote(previous, avatar, now)
    }

    fun onPlayerLeft(id: Int) {
        remotes.remove(id)
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
        private const val HEARTBEAT_MS = 1000L
    }
}
