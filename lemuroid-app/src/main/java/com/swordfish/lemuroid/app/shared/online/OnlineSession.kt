package com.swordfish.lemuroid.app.shared.online

import android.os.SystemClock
import com.swordfish.libretrodroid.LibretroDroid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * Either way, this class moves the packets over a WebSocket to the relay server. In trade &
 * battle rooms a dropped connection (a phone switching networks, or the app sent to the
 * background) is picked up again: the server keeps our seat and link for a few minutes and
 * we take it back with the token it gave us.
 *
 * [setCoreVariables] changes the running core's options (used to plug Gambatte into the bridge).
 */
class OnlineSession(
    private val config: OnlineConfig,
    private val scope: CoroutineScope,
    private val setCoreVariables: (List<Pair<String, String>>) -> Unit = { },
) {
    /** [away]: lost their connection; their seat is kept while they come back. */
    data class Player(val id: Int, val name: String, val away: Boolean = false)

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
            // Our connection dropped and we are getting our seat back.
            val reconnecting: Boolean = false,
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

        /** [linkedWithUs]: the room linked them with us right away. */
        data class PlayerJoined(val name: String, val linkedWithUs: Boolean) : Event

        data class PlayerLeft(val name: String) : Event

        data class PlayerAway(val name: String) : Event

        data class PlayerBack(val name: String) : Event

        data object Reconnected : Event

        /** [name] talked to us in the game: do we want to [kind] ("trade" or "battle")? */
        data class Invited(val from: Int, val name: String, val kind: String) : Event

        /** [name] answered our invitation. Saying yes linked us. */
        data class InviteAnswered(val from: Int, val name: String, val kind: String, val accepted: Boolean) : Event
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
        ROOM_CLOSED,
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

    /** Same world room: we share the map with the others and talk to them. */
    val sameWorld: Boolean get() = config.sameWorld

    /** Whether we made the room (false: we joined one). */
    val isCreator: Boolean get() = config.isHost

    /**
     * Whether the room server is recent enough for keeping seats and for invitations to trade or
     * battle (it hands out seat tokens since the same update).
     */
    val serverIsCurrent: Boolean get() = seat?.token != null

    /** The room's code once known (the one we joined, or the one the server gave our room). */
    val roomCode: String? get() = seat?.code ?: config.roomCode

    /** Where other players are in their game ("pos" messages), for sharing the world. */
    @Volatile var onPresence: ((from: Int, message: JSONObject) -> Unit)? = null

    @Volatile var onPlayerLeft: ((id: Int) -> Unit)? = null

    /** Tell the room where we are in our game. */
    fun sendPresence(message: JSONObject) {
        if (mutableState.value is State.InRoom) sendControl(message)
    }

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

    // Our place in the room, to take back after the connection drops. The token is null with
    // servers that don't keep seats; we then join the room again as a new player.
    private data class Seat(val code: String, val id: Int, val token: String?)

    @Volatile private var seat: Seat? = null

    @Volatile private var protocol: String = ""

    private val reconnecting = AtomicBoolean(false)
    private var reconnectJob: Job? = null
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)
    private val attemptResults = Channel<Boolean>(Channel.CONFLATED)
    private var connectAttempts = 0

    /** Call once the game is running. */
    // The trainer name from the player's game, when known; otherwise the name set in the app.
    @Volatile private var trainerName: String? = null
    private val playerName: String get() = trainerName ?: config.playerName

    /** Shows the others [name] (the player's in-game trainer name) instead of the app's name. Call before [start]. */
    fun useTrainerName(name: String?) {
        trainerName = name?.trim()?.takeIf { it.isNotEmpty() }
    }

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
        stopReconnecting()
        runCatching { webSocket?.send(JSONObject().put("t", "leave").toString()) }
        webSocket?.close(NORMAL_CLOSURE, "left")
        webSocket = null
        stopTransport()
        if (mutableState.value !is State.Failed) mutableState.value = State.Left
    }

    /** The game is on screen again: if we are reconnecting, try right now. */
    fun onForeground() {
        if (reconnecting.get()) wakeUps.trySend(Unit)
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

    /** Ask player [playerId] to "trade" or "battle", as if talking to them in the game. */
    fun invite(
        playerId: Int,
        kind: String,
    ) {
        sendControl(JSONObject().put("t", "invite").put("id", playerId).put("kind", kind))
    }

    fun answerInvite(
        playerId: Int,
        accept: Boolean,
    ) {
        sendControl(JSONObject().put("t", "invite_reply").put("id", playerId).put("accept", accept))
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
        this.protocol = protocol
        mutableState.value = State.Connecting
        openSocket()
    }

    private fun openSocket() {
        val request = Request.Builder().url(OnlineSettings.webSocketUrl(config.serverUrl)).build()
        webSocket = client.newWebSocket(request, Listener())
    }

    private fun helloMessage(): JSONObject {
        val seat = seat
        if (seat != null) {
            // Coming back: our own seat if the server keeps one, otherwise a new one.
            return if (seat.token != null) {
                JSONObject()
                    .put("t", "resume")
                    .put("code", seat.code)
                    .put("id", seat.id)
                    .put("token", seat.token)
                    .put("protocol", protocol)
            } else {
                joinMessage(seat.code)
            }
        }
        return if (config.isHost) {
            JSONObject()
                .put("t", "host")
                .put("kind", config.kind.serverKey)
                .put("name", playerName)
                .put("game", config.gameTitle)
                .put("system", config.systemId)
                .put("core", config.coreName)
                .put("mode", config.mode.serverKey)
                .put("delay", config.inputDelayFrames)
                .put("public", config.isPublic)
                .put("maxPlayers", config.maxPlayers)
                .put("protocol", protocol)
                .put("world", config.sameWorld)
        } else {
            joinMessage(config.roomCode)
        }
    }

    private fun joinMessage(code: String?): JSONObject =
        JSONObject()
            .put("t", "join")
            .put("code", code)
            .put("name", playerName)
            .put("protocol", protocol)

    // Events from sockets we already gave up on are ignored.
    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            webSocket.send(helloMessage().toString())
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (webSocket !== this@OnlineSession.webSocket) return
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            handleControlMessage(message)
        }

        override fun onMessage(
            webSocket: WebSocket,
            bytes: ByteString,
        ) {
            if (webSocket !== this@OnlineSession.webSocket) return
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
            if (webSocket !== this@OnlineSession.webSocket) return
            handleSocketEnd(code)
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (webSocket !== this@OnlineSession.webSocket) return
            Timber.w(t, "Online room connection failed")
            connectionLost()
        }
    }

    // The socket broke without a goodbye.
    private fun connectionLost() {
        if (!active.get()) return
        webSocket = null
        if (reconnecting.get()) {
            attemptResults.trySend(false)
            return
        }
        val inRoom = mutableState.value is State.InRoom
        when {
            // Trade & battle rooms keep our seat: go get it back.
            inRoom && config.kind == RoomKind.LINK && seat != null -> startReconnecting()
            // Not in the room yet: a couple more tries before giving up.
            !inRoom && connectAttempts < INITIAL_CONNECT_ATTEMPTS -> {
                connectAttempts++
                scope.launch(Dispatchers.IO) {
                    delay(connectAttempts * INITIAL_RETRY_DELAY_MS)
                    if (active.get() && webSocket == null) openSocket()
                }
            }
            else -> fail(if (inRoom) Reason.CONNECTION_LOST else Reason.CONNECTION_FAILED)
        }
    }

    private fun startReconnecting() {
        if (!reconnecting.compareAndSet(false, true)) return
        updateRoom { it.copy(reconnecting = true) }
        while (attemptResults.tryReceive().isSuccess) Unit
        reconnectJob =
            scope.launch(Dispatchers.IO) {
                val started = SystemClock.elapsedRealtime()
                var attempt = 0
                while (active.get() && reconnecting.get()) {
                    // Wait a little (less if the player comes back to the game).
                    withTimeoutOrNull(RECONNECT_DELAYS_MS[minOf(attempt, RECONNECT_DELAYS_MS.lastIndex)]) { wakeUps.receive() }
                    if (!active.get() || !reconnecting.get()) return@launch
                    if (SystemClock.elapsedRealtime() - started > RECONNECT_WINDOW_MS) {
                        fail(Reason.CONNECTION_LOST)
                        return@launch
                    }
                    openSocket()
                    val succeeded = withTimeoutOrNull(RECONNECT_ATTEMPT_TIMEOUT_MS) { attemptResults.receive() } ?: false
                    if (succeeded) return@launch
                    // Give up on this attempt's socket before it can report anything.
                    val stale = webSocket
                    webSocket = null
                    stale?.cancel()
                    while (attemptResults.tryReceive().isSuccess) Unit
                    attempt++
                }
            }
    }

    private fun stopReconnecting() {
        reconnecting.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun handleControlMessage(message: JSONObject) {
        when (message.optString("t")) {
            "hosted", "joined" -> {
                val clientId = message.getInt("clientId")
                val room = message.getJSONObject("room")
                val code = room.getString("code")
                seat = Seat(code, clientId, message.optString("token").takeIf { it.isNotEmpty() })
                val inRoom =
                    State.InRoom(
                        code = code,
                        clientId = clientId,
                        players = parsePlayers(message),
                        maxPlayers = room.optInt("maxPlayers", config.maxPlayers),
                        kind = RoomKind.fromServerKey(room.optString("kind")),
                        ownerId = message.optInt("ownerId", 0),
                        groups = parseGroups(message.optJSONArray("groups")),
                    )
                mutableState.value = inRoom
                connectAttempts = 0
                if (reconnecting.getAndSet(false)) {
                    attemptResults.trySend(true)
                    mutableEvents.tryEmit(Event.Reconnected)
                }
                val inputDelay = room.optInt("delay", config.inputDelayFrames)
                startTransport(clientId, inputDelay)
                transport.groupChanged(clientId, inRoom.myGroup)
            }

            "peer_joined" -> {
                val id = message.getInt("id")
                val name = message.optString("name")
                updateRoom { it.copy(players = (it.players.filter { p -> p.id != id } + Player(id, name)).sortedBy { p -> p.id }) }
                if (isHost()) transport.peerJoined(id)
                pendingJoins[id] = name
                // In trade & battle rooms a "groups" message may follow right away, linking them
                // with us; give it a moment so the notice can say so.
                if (config.kind == RoomKind.LINK) {
                    scope.launch {
                        delay(JOIN_NOTICE_DELAY_MS)
                        announceJoin(id)
                    }
                } else {
                    announceJoin(id)
                }
            }

            "peer_away" -> {
                val id = message.optInt("id", -1)
                val name = (mutableState.value as? State.InRoom)?.playerName(id).orEmpty()
                updateRoom { room -> room.copy(players = room.players.map { if (it.id == id) it.copy(away = true) else it }) }
                if (name.isNotEmpty()) mutableEvents.tryEmit(Event.PlayerAway(name))
            }

            "peer_back" -> {
                val id = message.optInt("id", -1)
                val name = (mutableState.value as? State.InRoom)?.playerName(id).orEmpty()
                updateRoom { room -> room.copy(players = room.players.map { if (it.id == id) it.copy(away = false) else it }) }
                if (name.isNotEmpty()) mutableEvents.tryEmit(Event.PlayerBack(name))
            }

            "peer_left" -> {
                val id = message.getInt("id")
                val name = (mutableState.value as? State.InRoom)?.playerName(id).orEmpty()
                if (name.isNotEmpty() && pendingJoins.remove(id) == null) mutableEvents.tryEmit(Event.PlayerLeft(name))
                updateRoom { room ->
                    room.copy(
                        players = room.players.filter { p -> p.id != id },
                        incomingRequests = room.incomingRequests.filter { p -> p.id != id },
                        outgoingRequest = room.outgoingRequest.takeIf { it != id },
                    )
                }
                if (isHost()) transport.peerLeft(id)
                onPlayerLeft?.invoke(id)
            }

            "pos" -> {
                val from = message.optInt("from", -1)
                if (from >= 0) onPresence?.invoke(from, message)
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
                pendingJoins.keys.toList().forEach { announceJoin(it) }
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

            "invite" -> {
                val from = message.optInt("from", -1)
                if (from < 0) return
                mutableEvents.tryEmit(Event.Invited(from, message.optString("name"), message.optString("kind", "trade")))
            }

            "invite_reply" -> {
                val from = message.optInt("from", -1)
                val name = (mutableState.value as? State.InRoom)?.playerName(from).orEmpty()
                mutableEvents.tryEmit(
                    Event.InviteAnswered(from, name, message.optString("kind", "trade"), message.optBoolean("accept", false)),
                )
            }

            "closed" -> fail(Reason.HOST_LEFT)

            "kicked" -> fail(Reason.REMOVED)

            "error" -> {
                val code = message.optString("code")
                val current = seat
                // Our seat is gone (we were away too long): join the room again as a new player.
                if (code == "resume_failed" && current != null) {
                    seat = current.copy(token = null)
                    webSocket?.send(joinMessage(current.code).toString())
                    return
                }
                val reason =
                    when {
                        code == "not_found" && current != null -> Reason.ROOM_CLOSED
                        code == "not_found" -> Reason.ROOM_NOT_FOUND
                        code == "room_full" -> Reason.ROOM_FULL
                        code == "protocol_mismatch" -> Reason.VERSION_MISMATCH
                        else -> Reason.SERVER_ERROR
                    }
                fail(reason, message.optString("message").takeIf { it.isNotBlank() })
            }
        }
    }

    // Players who just joined, until we know whether they were linked with us.
    private val pendingJoins = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun announceJoin(id: Int) {
        val name = pendingJoins.remove(id) ?: return
        val room = mutableState.value as? State.InRoom
        val linked = room?.myGroup?.any { it.id == id } == true
        mutableEvents.tryEmit(Event.PlayerJoined(name, linked))
    }

    private fun parsePlayers(message: JSONObject): List<Player> {
        val players = message.optJSONArray("players") ?: return emptyList()
        return (0 until players.length())
            .map { players.getJSONObject(it) }
            .map { Player(it.getInt("id"), it.optString("name"), it.optBoolean("away", false)) }
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
            else -> connectionLost()
        }
    }

    private fun fail(
        reason: Reason,
        message: String? = null,
    ) {
        // A more specific reason (like "host left") may already be set; keep it.
        if (mutableState.value !is State.Failed) mutableState.value = State.Failed(reason, message)
        active.set(false)
        stopReconnecting()
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

        private const val JOIN_NOTICE_DELAY_MS = 600L
        private const val INITIAL_CONNECT_ATTEMPTS = 2
        private const val INITIAL_RETRY_DELAY_MS = 1_500L

        // The server keeps a seat for 3 minutes; stop trying a little before that.
        private const val RECONNECT_WINDOW_MS = 170_000L
        private val RECONNECT_DELAYS_MS = longArrayOf(500, 1_500, 3_000, 5_000, 8_000)
        private const val RECONNECT_ATTEMPT_TIMEOUT_MS = 12_000L

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
