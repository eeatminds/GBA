package com.swordfish.lemuroid.app.shared.online

import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Connects a running game to an online room.
 *
 * - Trade & battle rooms hold up to 8 players, who link up in small groups like plugging in
 *   link cables. GBA games link through gpSP's netpacket interface; Game Boy games through
 *   Gambatte's network link and [GbLinkBridge].
 * - Play together rooms: LibretroDroid's netplay engine sends inputs and game states. Client
 *   id 0 is the host.
 *
 * Either way, this class moves the packets over a WebSocket to the relay server.
 *
 * [setCoreVariables] changes the running core's options (used to plug Gambatte into the bridge).
 */
class OnlineSession(
    private val config: OnlineConfig,
    private val scope: CoroutineScope,
    private val setCoreVariables: (List<Pair<String, String>>) -> Unit = { },
) {
    data class Player(val id: Int, val name: String)

    /** A player's place in a link group. Slot 0 leads the link. */
    data class LinkMember(val id: Int, val slot: Int)

    sealed interface State {
        data object WaitingForGame : State

        data object Connecting : State

        data class InRoom(
            val code: String,
            val clientId: Int,
            val players: List<Player>,
            val maxPlayers: Int,
            val kind: RoomKind,
            val ownerId: Int = 0,
            val groups: List<List<LinkMember>> = emptyList(),
            // Players asking to link with us, oldest first.
            val incomingRequests: List<Player> = emptyList(),
            // The player we asked, while waiting for their answer.
            val outgoingRequest: Int? = null,
            val sync: Sync = Sync.OK,
        ) : State {
            val isHost: Boolean get() = clientId == 0

            val myGroup: List<LinkMember>? get() = groups.firstOrNull { group -> group.any { it.id == clientId } }

            fun groupOf(playerId: Int): List<LinkMember>? = groups.firstOrNull { group -> group.any { it.id == playerId } }

            fun playerName(id: Int): String = players.firstOrNull { it.id == id }?.name.orEmpty()

            /** Names of the players linked with us. */
            val linkedNames: List<String>
                get() = myGroup.orEmpty().filter { it.id != clientId }.map { playerName(it.id) }
        }

        data class Failed(val reason: Reason, val message: String? = null) : State

        data object Left : State
    }

    /** One-off things to tell the player. */
    sealed interface Event {
        data class LinkDeclined(val name: String) : Event

        data class LinkError(val message: String) : Event
    }

    /** Play together rooms only: whether the game is waiting on the network. */
    enum class Sync { OK, WAITING_FOR_HOST, WAITING_FOR_PLAYERS }

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

    private val mutableEvents = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = mutableEvents.asSharedFlow()

    val serverUrl: String get() = config.serverUrl

    /** Whether players in this room link up with each other (trade & battle rooms). */
    val hasLinks: Boolean get() = config.kind == RoomKind.LINK

    private val transport: Transport =
        when {
            config.kind == RoomKind.NETPLAY -> NetplayTransport()
            config.isGameBoyLink -> GameBoyLinkTransport()
            else -> NetpacketTransport()
        }

    private val active = AtomicBoolean(false)
    private val transportStarted = AtomicBoolean(false)

    @Volatile private var webSocket: WebSocket? = null

    private var syncJob: Job? = null

    /** Call once the game is running. */
    fun start() {
        if (!active.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            val protocol =
                when {
                    config.kind == RoomKind.NETPLAY -> config.protocol
                    config.isGameBoyLink -> {
                        if (!(transport as GameBoyLinkTransport).plugIn()) {
                            fail(Reason.NOT_SUPPORTED)
                            return@launch
                        }
                        config.protocol
                    }
                    else -> {
                        // gpSP registers its link interface while the game loads.
                        if (!waitForCoreSupport()) {
                            if (active.get()) fail(Reason.NOT_SUPPORTED)
                            return@launch
                        }
                        LibretroDroid.netpacketProtocolVersion().orEmpty()
                    }
                }
            if (active.get()) connect(protocol)
        }
    }

    fun close() {
        if (!active.getAndSet(false)) return
        runCatching { webSocket?.send(JSONObject().put("t", "leave").toString()) }
        webSocket?.close(NORMAL_CLOSURE, "left")
        webSocket = null
        stopTransport()
        if (mutableState.value !is State.Failed) mutableState.value = State.Left
    }

    /** Ask another player to link with us. */
    fun requestLink(playerId: Int) {
        sendControl(JSONObject().put("t", "link_request").put("id", playerId))
        updateRoom { it.copy(outgoingRequest = playerId) }
    }

    fun acceptLink(playerId: Int) {
        sendControl(JSONObject().put("t", "link_accept").put("id", playerId))
        updateRoom { room -> room.copy(incomingRequests = room.incomingRequests.filter { it.id != playerId }) }
    }

    fun declineLink(playerId: Int) {
        sendControl(JSONObject().put("t", "link_decline").put("id", playerId))
        updateRoom { room -> room.copy(incomingRequests = room.incomingRequests.filter { it.id != playerId }) }
    }

    /** Unplug our link cable. */
    fun unlink() {
        sendControl(JSONObject().put("t", "unlink"))
    }

    private fun sendControl(message: JSONObject) {
        runCatching { webSocket?.send(message.toString()) }
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

    private fun helloMessage(protocol: String): JSONObject {
        return if (config.isHost) {
            JSONObject()
                .put("t", "host")
                .put("kind", config.kind.serverKey)
                .put("name", config.playerName)
                .put("game", config.gameTitle)
                .put("system", config.systemId)
                .put("core", config.coreName)
                .put("mode", config.mode.serverKey)
                .put("delay", config.inputDelayFrames)
                .put("public", config.isPublic)
                .put("maxPlayers", config.maxPlayers)
                .put("protocol", protocol)
        } else {
            JSONObject()
                .put("t", "join")
                .put("code", config.roomCode)
                .put("name", config.playerName)
                .put("protocol", protocol)
        }
    }

    private inner class Listener(private val protocol: String) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            webSocket.send(helloMessage(protocol).toString())
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
            if (!transportStarted.get() || bytes.size < 3) return
            val data = bytes.toByteArray()
            val from = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
            transport.push(from, data.copyOfRange(2, data.size))
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
                val inRoom =
                    State.InRoom(
                        code = room.getString("code"),
                        clientId = clientId,
                        players = parsePlayers(message),
                        maxPlayers = room.optInt("maxPlayers", config.maxPlayers),
                        kind = RoomKind.fromServerKey(room.optString("kind")),
                        ownerId = message.optInt("ownerId", 0),
                        groups = parseGroups(message.optJSONArray("groups")),
                    )
                mutableState.value = inRoom
                val inputDelay = room.optInt("delay", config.inputDelayFrames)
                startTransport(clientId, inputDelay)
                transport.groupChanged(clientId, inRoom.myGroup)
            }

            "peer_joined" -> {
                val id = message.getInt("id")
                val name = message.optString("name")
                updateRoom { it.copy(players = (it.players.filter { p -> p.id != id } + Player(id, name)).sortedBy { p -> p.id }) }
                if (isHost()) transport.peerJoined(id)
            }

            "peer_left" -> {
                val id = message.getInt("id")
                updateRoom { room ->
                    room.copy(
                        players = room.players.filter { p -> p.id != id },
                        incomingRequests = room.incomingRequests.filter { p -> p.id != id },
                        outgoingRequest = room.outgoingRequest.takeIf { it != id },
                    )
                }
                if (isHost()) transport.peerLeft(id)
            }

            "groups" -> {
                val groups = parseGroups(message.optJSONArray("groups"))
                updateRoom { room ->
                    val myGroup = groups.firstOrNull { group -> group.any { it.id == room.clientId } }
                    room.copy(
                        groups = groups,
                        outgoingRequest = room.outgoingRequest.takeIf { id -> myGroup?.none { it.id == id } ?: true },
                    )
                }
                val room = mutableState.value as? State.InRoom ?: return
                transport.groupChanged(room.clientId, room.myGroup)
            }

            "owner" -> updateRoom { it.copy(ownerId = message.optInt("id", it.ownerId)) }

            "link_request" -> {
                val from = message.optInt("from", -1)
                if (from < 0) return
                val player = Player(from, message.optString("name"))
                updateRoom { room -> room.copy(incomingRequests = room.incomingRequests.filter { it.id != from } + player) }
            }

            "link_declined" -> {
                val id = message.optInt("id", -1)
                val room = mutableState.value as? State.InRoom ?: return
                updateRoom { it.copy(outgoingRequest = null) }
                mutableEvents.tryEmit(Event.LinkDeclined(room.playerName(id)))
            }

            "link_error" -> {
                updateRoom { it.copy(outgoingRequest = null) }
                mutableEvents.tryEmit(Event.LinkError(message.optString("message")))
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
        mutableState.update { current -> if (current is State.InRoom) update(current) else current }
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
        stopTransport()
    }

    private fun startTransport(
        clientId: Int,
        inputDelay: Int,
    ) {
        if (!transportStarted.compareAndSet(false, true)) return
        transport.start(clientId, inputDelay)
        Thread({ pumpOutgoing() }, "online-pump").apply {
            isDaemon = true
            start()
        }
        if (transport is NetplayTransport) {
            syncJob =
                scope.launch(Dispatchers.Default) {
                    while (isActive && transportStarted.get()) {
                        val sync =
                            when (LibretroDroid.netplayStatus()) {
                                LibretroDroid.NETPLAY_STATUS_WAITING_FOR_HOST -> Sync.WAITING_FOR_HOST
                                LibretroDroid.NETPLAY_STATUS_WAITING_FOR_PLAYERS -> Sync.WAITING_FOR_PLAYERS
                                else -> Sync.OK
                            }
                        updateRoom { if (it.sync == sync) it else it.copy(sync = sync) }
                        delay(SYNC_POLL_MS)
                    }
                }
        }
    }

    private fun stopTransport() {
        val wasStarted = transportStarted.getAndSet(false)
        syncJob?.cancel()
        syncJob = null
        if (wasStarted || transport is GameBoyLinkTransport) runCatching { transport.stop() }
    }

    // Takes packets the emulator wants to send and forwards them to the server.
    private fun pumpOutgoing() {
        while (transportStarted.get()) {
            val message = transport.take(PUMP_TIMEOUT_MS) ?: continue
            if (message.size < 3) continue
            val localTarget = ((message[1].toInt() and 0xFF) shl 8) or (message[2].toInt() and 0xFF)
            val target = transport.roomTarget(localTarget) ?: continue
            val socket = webSocket ?: continue
            when (message[0].toInt()) {
                LibretroDroid.NETPACKET_OUTGOING_DATA -> {
                    if (message.size <= 3) continue
                    val packet = ByteArray(message.size - 1)
                    packet[0] = (target shr 8).toByte()
                    packet[1] = target.toByte()
                    message.copyInto(packet, destinationOffset = 2, startIndex = 3)
                    socket.send(packet.toByteString())
                }

                LibretroDroid.NETPACKET_OUTGOING_REJECT_PEER -> {
                    // The core refused a player: take them off our link (or out of a play
                    // together room).
                    val control =
                        if (config.kind == RoomKind.LINK) {
                            JSONObject().put("t", "unlink").put("id", target)
                        } else {
                            JSONObject().put("t", "kick").put("id", target).put("reason", "full")
                        }
                    socket.send(control.toString())
                }
            }
        }
    }

    /** The emulator side of a room. */
    private interface Transport {
        fun start(
            clientId: Int,
            inputDelay: Int,
        )

        fun stop()

        fun push(
            from: Int,
            data: ByteArray,
        )

        fun peerJoined(id: Int) {}

        fun peerLeft(id: Int) {}

        /** Our link group changed (trade & battle rooms). Null when we are not linked. */
        fun groupChanged(
            myId: Int,
            group: List<LinkMember>?,
        ) {}

        /** Packet target as the emulator numbers players -> room player id (or broadcast). */
        fun roomTarget(localTarget: Int): Int? = localTarget

        fun take(timeoutMs: Int): ByteArray?
    }

    /** GBA link through gpSP; see [NetpacketLink]. */
    private class NetpacketTransport : Transport {
        private val link = NetpacketLink(NetpacketLink.LibretroCore)

        override fun start(
            clientId: Int,
            inputDelay: Int,
        ) {
            // The link starts once we are in a group.
        }

        override fun groupChanged(
            myId: Int,
            group: List<LinkMember>?,
        ) = link.groupChanged(myId, group)

        override fun stop() = link.stop()

        override fun push(
            from: Int,
            data: ByteArray,
        ) = link.push(from, data)

        override fun roomTarget(localTarget: Int): Int? = link.roomTarget(localTarget)

        override fun take(timeoutMs: Int): ByteArray? = LibretroDroid.netpacketTakeOutgoing(timeoutMs)
    }

    /** Game Boy link through Gambatte's network link and a local bridge. */
    private inner class GameBoyLinkTransport : Transport {
        private val bridge = GbLinkBridge()

        /** Starts the bridge and points Gambatte at it. */
        fun plugIn(): Boolean {
            val port = bridge.start() ?: return false
            setCoreVariables(GbLinkBridge.coreVariables(port))
            return true
        }

        override fun start(
            clientId: Int,
            inputDelay: Int,
        ) {}

        override fun stop() {
            bridge.stop()
            runCatching { setCoreVariables(GbLinkBridge.DISCONNECT_VARIABLES) }
        }

        override fun groupChanged(
            myId: Int,
            group: List<LinkMember>?,
        ) {
            bridge.setPeer(group?.takeIf { it.size == 2 }?.firstOrNull { it.id != myId }?.id)
        }

        override fun push(
            from: Int,
            data: ByteArray,
        ) = bridge.fromPeer(from, data)

        override fun take(timeoutMs: Int): ByteArray? = bridge.takeOutgoing(timeoutMs)
    }

    private class NetplayTransport : Transport {
        override fun start(
            clientId: Int,
            inputDelay: Int,
        ) = LibretroDroid.netplayStart(clientId, inputDelay)

        override fun stop() {
            LibretroDroid.netplayStop()
            LibretroDroid.netplayWakeTakers()
        }

        override fun push(
            from: Int,
            data: ByteArray,
        ) = LibretroDroid.netplayPushIncoming(from, data)

        override fun peerJoined(id: Int) = LibretroDroid.netplayPeerJoined(id)

        override fun peerLeft(id: Int) = LibretroDroid.netplayPeerLeft(id)

        override fun take(timeoutMs: Int): ByteArray? = LibretroDroid.netplayTakeOutgoing(timeoutMs)
    }

    companion object {
        private const val NORMAL_CLOSURE = 1000
        private const val CLOSE_HOST_LEFT = 4000
        private const val CLOSE_KICKED = 4001
        private const val PUMP_TIMEOUT_MS = 250
        private const val CORE_SUPPORT_POLL_MS = 250L
        private const val CORE_SUPPORT_ATTEMPTS = 60
        private const val SYNC_POLL_MS = 300L

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
        }

        fun parseGroups(groups: JSONArray?): List<List<LinkMember>> {
            if (groups == null) return emptyList()
            return (0 until groups.length()).map { index ->
                val group = groups.getJSONArray(index)
                (0 until group.length())
                    .map { group.getJSONObject(it) }
                    .map { LinkMember(it.getInt("id"), it.getInt("slot")) }
                    .sortedBy { it.slot }
            }
        }
    }
}

private fun <T> MutableStateFlow<T>.update(function: (T) -> T) {
    while (true) {
        val current = value
        if (compareAndSet(current, function(current))) return
    }
}
