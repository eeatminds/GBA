package com.swordfish.lemuroid.app.shared.online

import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects a running game to an online room.
 *
 * The emulator core (gpSP) sends and receives link cable packets through LibretroDroid's
 * netpacket bridge. This class moves those packets over a WebSocket to the relay server.
 * Client id 0 is the host; joiners get 1, 2, 3...
 */
class OnlineSession(
    private val config: OnlineConfig,
    private val scope: CoroutineScope,
) {
    data class Player(val id: Int, val name: String)

    sealed interface State {
        data object WaitingForGame : State

        data object Connecting : State

        data class InRoom(
            val code: String,
            val clientId: Int,
            val players: List<Player>,
            val maxPlayers: Int,
            val mode: LinkMode,
        ) : State {
            val isHost: Boolean get() = clientId == 0
        }

        data class Failed(val reason: Reason, val message: String? = null) : State

        data object Left : State
    }

    enum class Reason {
        NOT_SUPPORTED,
        CONNECTION_FAILED,
        CONNECTION_LOST,
        ROOM_NOT_FOUND,
        ROOM_FULL,
        VERSION_MISMATCH,
        HOST_LEFT,
        REMOVED,
        SERVER_ERROR,
    }

    private val mutableState = MutableStateFlow<State>(State.WaitingForGame)
    val state: StateFlow<State> = mutableState.asStateFlow()

    val serverUrl: String get() = config.serverUrl

    private val active = AtomicBoolean(false)
    private val netpacketStarted = AtomicBoolean(false)
    @Volatile private var webSocket: WebSocket? = null

    @Volatile private var pumpThread: Thread? = null

    /** Call once the game is running. Waits for the core to register its netpacket interface. */
    fun start() {
        if (!active.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val supported = waitForCoreSupport()
            if (!active.get()) return@launch
            if (!supported) {
                fail(Reason.NOT_SUPPORTED)
                return@launch
            }
            connect(LibretroDroid.netpacketProtocolVersion().orEmpty())
        }
    }

    fun close() {
        if (!active.getAndSet(false)) return
        runCatching { webSocket?.send(JSONObject().put("t", "leave").toString()) }
        webSocket?.close(NORMAL_CLOSURE, "left")
        webSocket = null
        stopNetpacket()
        if (mutableState.value !is State.Failed) mutableState.value = State.Left
    }

    private suspend fun waitForCoreSupport(): Boolean {
        repeat(CORE_SUPPORT_ATTEMPTS) {
            if (!active.get()) return false
            if (runCatching { LibretroDroid.netpacketIsSupported() }.getOrDefault(false)) return true
            delay(CORE_SUPPORT_POLL_MS)
        }
        return false
    }

    private fun connect(protocol: String) {
        mutableState.value = State.Connecting
        val request = Request.Builder().url(OnlineSettings.webSocketUrl(config.serverUrl)).build()
        webSocket = client.newWebSocket(request, Listener(protocol))
    }

    private inner class Listener(private val protocol: String) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            val hello =
                if (config.isHost) {
                    JSONObject()
                        .put("t", "host")
                        .put("name", config.playerName)
                        .put("game", config.gameTitle)
                        .put("system", "gba")
                        .put("mode", config.mode.serverKey)
                        .put("public", config.isPublic)
                        .put("maxPlayers", config.mode.maxPlayers)
                        .put("protocol", protocol)
                } else {
                    JSONObject()
                        .put("t", "join")
                        .put("code", config.roomCode)
                        .put("name", config.playerName)
                        .put("protocol", protocol)
                }
            webSocket.send(hello.toString())
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            handleControlMessage(message)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            if (!netpacketStarted.get() || bytes.size < 3) return
            val data = bytes.toByteArray()
            val from = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            LibretroDroid.netpacketPushIncoming(from, data.copyOfRange(2, data.size))
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            handleSocketEnd(code)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            Timber.w(t, "Online room connection failed")
            if (!active.get()) return
            val wasInRoom = mutableState.value is State.InRoom
            fail(if (wasInRoom) Reason.CONNECTION_LOST else Reason.CONNECTION_FAILED)
        }
    }

    private fun handleControlMessage(message: JSONObject) {
        when (message.optString("t")) {
            "hosted", "joined" -> {
                val clientId = message.getInt("clientId")
                val room = message.getJSONObject("room")
                mutableState.value =
                    State.InRoom(
                        code = room.getString("code"),
                        clientId = clientId,
                        players = parsePlayers(message),
                        maxPlayers = room.optInt("maxPlayers", config.mode.maxPlayers),
                        mode = LinkMode.fromServerKey(room.optString("mode")),
                    )
                startNetpacket(clientId)
            }

            "peer_joined" -> {
                val id = message.getInt("id")
                val name = message.optString("name")
                updateRoom { it.copy(players = (it.players.filter { p -> p.id != id } + Player(id, name)).sortedBy { p -> p.id }) }
                if (isHost()) LibretroDroid.netpacketPeerConnected(id)
            }

            "peer_left" -> {
                val id = message.getInt("id")
                updateRoom { it.copy(players = it.players.filter { p -> p.id != id }) }
                if (isHost()) LibretroDroid.netpacketPeerDisconnected(id)
            }

            "closed" -> fail(Reason.HOST_LEFT)

            "kicked" -> fail(Reason.REMOVED)

            "error" -> {
                val reason =
                    when (message.optString("code")) {
                        "not_found" -> Reason.ROOM_NOT_FOUND
                        "room_full" -> Reason.ROOM_FULL
                        "protocol_mismatch" -> Reason.VERSION_MISMATCH
                        else -> Reason.SERVER_ERROR
                    }
                fail(reason, message.optString("message").takeIf { it.isNotBlank() })
            }
        }
    }

    private fun parsePlayers(message: JSONObject): List<Player> {
        val players = message.optJSONArray("players") ?: return emptyList()
        return (0 until players.length())
            .map { players.getJSONObject(it) }
            .map { Player(it.getInt("id"), it.optString("name")) }
            .sortedBy { it.id }
    }

    private fun isHost(): Boolean = (mutableState.value as? State.InRoom)?.isHost == true

    private fun updateRoom(update: (State.InRoom) -> State.InRoom) {
        val current = mutableState.value as? State.InRoom ?: return
        mutableState.value = update(current)
    }

    private fun handleSocketEnd(code: Int) {
        if (!active.get()) return
        when (code) {
            CLOSE_HOST_LEFT -> fail(Reason.HOST_LEFT)
            CLOSE_KICKED -> fail(Reason.REMOVED)
            NORMAL_CLOSURE -> close()
            else -> fail(Reason.CONNECTION_LOST)
        }
    }

    private fun fail(
        reason: Reason,
        message: String? = null,
    ) {
        // A more specific reason (like "host left") may already be set; keep it.
        if (mutableState.value !is State.Failed) mutableState.value = State.Failed(reason, message)
        active.set(false)
        webSocket?.close(NORMAL_CLOSURE, null)
        webSocket = null
        stopNetpacket()
    }

    private fun startNetpacket(clientId: Int) {
        if (!netpacketStarted.compareAndSet(false, true)) return
        LibretroDroid.netpacketStart(clientId)
        pumpThread =
            Thread({ pumpOutgoing() }, "online-link-pump").apply {
                isDaemon = true
                start()
            }
    }

    private fun stopNetpacket() {
        if (!netpacketStarted.getAndSet(false)) return
        runCatching {
            LibretroDroid.netpacketStop()
            LibretroDroid.netpacketWakeTakers()
        }
        pumpThread = null
    }

    // Takes packets the core wants to send and forwards them to the server.
    private fun pumpOutgoing() {
        while (netpacketStarted.get()) {
            val message = LibretroDroid.netpacketTakeOutgoing(PUMP_TIMEOUT_MS) ?: continue
            if (message.size < 3) continue
            val target = ((message[1].toInt() and 0xFF) shl 8) or (message[2].toInt() and 0xFF)
            val socket = webSocket ?: continue
            when (message[0].toInt()) {
                LibretroDroid.NETPACKET_OUTGOING_DATA -> {
                    if (message.size > 3) socket.send(message.toByteString(1, message.size - 1))
                }

                LibretroDroid.NETPACKET_OUTGOING_REJECT_PEER -> {
                    socket.send(JSONObject().put("t", "kick").put("id", target).put("reason", "full").toString())
                }
            }
        }
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val CLOSE_HOST_LEFT = 4000
        private const val CLOSE_KICKED = 4001
        private const val PUMP_TIMEOUT_MS = 250
        private const val CORE_SUPPORT_POLL_MS = 250L
        private const val CORE_SUPPORT_ATTEMPTS = 60

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
