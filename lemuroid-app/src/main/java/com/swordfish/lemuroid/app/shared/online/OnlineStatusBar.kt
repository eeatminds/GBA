package com.swordfish.lemuroid.app.shared.online

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel
import com.swordfish.lemuroid.app.shared.online.world.GameViewport
import com.swordfish.lemuroid.app.shared.online.world.Talk
import com.swordfish.lemuroid.app.shared.online.world.TalkBox
import com.swordfish.lemuroid.app.shared.online.world.WorldSession
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * The room, shown over the game screen:
 * - while the game and the connection start, a card asking to wait;
 * - then a small status that sits in free space (under the game in portrait, beside it in
 *   landscape) so it never covers the game or its menu button. Tapping it opens the room.
 */
@Composable
fun OnlineStatusBar(
    session: OnlineSession,
    gameTitle: String,
    world: WorldSession? = null,
    aPresses: Flow<Unit>? = null,
    // The game's buttons for the talk box, and a switch that keeps them from the game meanwhile.
    menuKeys: Flow<BaseGameScreenViewModel.MenuKey>? = null,
    onCaptureInput: (Boolean) -> Unit = {},
) {
    val state by session.state.collectAsState()
    val context = LocalContext.current
    var showRoom by remember { mutableStateOf(false) }
    val isPokemon = remember(gameTitle) { POKEMON.containsMatchIn(gameTitle) }
    // A conversation with another player, shown as a text box in the game.
    var talk by remember { mutableStateOf<Talk?>(null) }

    // The world appears once the game runs (after this starts listening): always use the latest.
    val currentWorld by rememberUpdatedState(world)

    // Who joined, left or dropped out, and answers to our link requests, as short notices.
    // Invitations to trade or battle become a conversation instead.
    LaunchedEffect(session) {
        session.events.collect { event ->
            when (event) {
                is OnlineSession.Event.Invited -> {
                    val current = talk
                    if (current is Talk.Connecting || current is Talk.Waiting || currentWorld?.linking == true) {
                        // One trade or battle at a time: we are already linking, or waiting for
                        // an answer. Saying yes now would start a second one over the first.
                        LinkLog.add("talk: ${event.from} asks to ${event.kind} while we are busy: no")
                        session.answerInvite(event.from, false)
                    } else {
                        talk = Talk.Invited(event.from, event.name, event.kind)
                        // Someone wants to trade or battle: hard to miss, even mid-game.
                        buzz(context)
                    }
                }
                is OnlineSession.Event.InviteAnswered -> {
                    val current = talk
                    if (current is Talk.Waiting && current.playerId == event.from && current.kind == event.kind) {
                        talk =
                            if (event.accepted && currentWorld?.startInPlace(current.kind) == true) {
                                // They said yes: the trade or battle starts right away.
                                Talk.Connecting(current.name, current.kind)
                            } else {
                                Talk.Result(current.name, current.kind, event.accepted)
                            }
                    } else if (event.accepted) {
                        // A yes we no longer wait for (we cancelled, or asked something else
                        // since): their game started linking, so tell it to stop waiting for ours.
                        LinkLog.add("talk: ${event.from} said yes to a ${event.kind} we no longer ask for: never mind")
                        session.gameLeftLink()
                    }
                }
                else -> {
                    val text = noticeText(context, event, isPokemon)
                    Toast.makeText(context, text, if (event is OnlineSession.Event.PlayerJoined) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // A pressed while facing another player: talk to them.
    LaunchedEffect(world, aPresses) {
        if (world == null || aPresses == null) return@LaunchedEffect
        aPresses.collect {
            if (talk != null) return@collect
            val room = session.state.value as? OnlineSession.State.InRoom ?: return@collect
            val id = world.playerInFront() ?: return@collect
            if (!session.serverIsCurrent) {
                Toast.makeText(context, R.string.talk_server_outdated, Toast.LENGTH_LONG).show()
                return@collect
            }
            talk = Talk.Ask(id, room.playerName(id).ifBlank { context.getString(R.string.online_player_fallback, id + 1) })
        }
    }

    // The link coming up (or not) closes the "connecting" box.
    val linkStatus = world?.linkStatus?.collectAsState()?.value
    LaunchedEffect(linkStatus) {
        val connecting = talk as? Talk.Connecting ?: return@LaunchedEffect
        when (linkStatus) {
            WorldSession.LinkStatus.Started -> talk = null
            WorldSession.LinkStatus.Failed -> talk = Talk.LinkFailed(connecting.name, connecting.kind)
            else -> Unit
        }
    }

    // While a conversation is open, the pad and A/B answer it instead of moving the player
    // (not while connecting: the game takes over right after).
    val talking = talk != null && talk !is Talk.Connecting
    LaunchedEffect(talking) { onCaptureInput(talking) }
    DisposableEffect(Unit) { onDispose { onCaptureInput(false) } }

    // Nobody answers forever.
    LaunchedEffect(talk) {
        val waiting = talk as? Talk.Waiting ?: return@LaunchedEffect
        delay(INVITE_TIMEOUT_MS)
        if (talk == waiting) talk = Talk.Result(waiting.name, waiting.kind, accepted = false, answered = false)
    }

    val room = state as? OnlineSession.State.InRoom
    if (room != null) {
        room.incomingRequests.firstOrNull()?.let { player ->
            LinkRequestDialog(
                player = player,
                onAccept = { session.acceptLink(player.id) },
                onDecline = { session.declineLink(player.id) },
            )
        }
        if (showRoom) {
            OnlineRoomSheet(
                room = room,
                hasLinks = session.hasLinks,
                isPokemon = isPokemon,
                onLink = { session.requestLink(it) },
                onUnlink = { session.unlink() },
                onInvite = { shareRoom(context, session.serverUrl, room.code, gameTitle) },
                onDismiss = { showRoom = false },
                world = world,
            )
        }
    }

    OnlineRoomStatus(
        state = state,
        hasLinks = session.hasLinks,
        isCreator = session.isCreator,
        roomCode = session.roomCode,
        isPokemon = isPokemon,
        onOpenRoom = { showRoom = true },
        sameWorld = session.sameWorld,
    )

    talk?.let { current ->
        TalkBox(
            talk = current,
            isPokemon = isPokemon,
            onAsk = { id, kind ->
                session.invite(id, kind)
                talk = Talk.Waiting(id, (current as? Talk.Ask)?.name.orEmpty(), kind)
            },
            onAnswer = { id, accept ->
                session.answerInvite(id, accept)
                val invited = current as? Talk.Invited
                talk =
                    when {
                        !accept || invited == null -> null
                        // Trade or battle right here; the game's own screens take over.
                        currentWorld?.startInPlace(invited.kind) == true -> Talk.Connecting(invited.name, invited.kind)
                        else -> Talk.Result(invited.name, invited.kind, accepted = true)
                    }
            },
            onClose = {
                talk = null
                world?.clearLinkStatus()
            },
            keys = menuKeys,
        )
    }
}

// Two short pulses.
private fun buzz(context: Context) {
    runCatching {
        val vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return
        val pattern = longArrayOf(0, 90, 90, 90)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}

private const val INVITE_TIMEOUT_MS = 30_000L

/** The loading card, then the room status in free space. Drawn from [state] alone. */
@Composable
fun OnlineRoomStatus(
    state: OnlineSession.State,
    hasLinks: Boolean,
    isCreator: Boolean,
    roomCode: String?,
    isPokemon: Boolean,
    onOpenRoom: () -> Unit,
    sameWorld: Boolean = false,
) {
    if (state == OnlineSession.State.WaitingForGame || state == OnlineSession.State.Connecting) {
        LoadingCard(
            title =
                if (isCreator) {
                    stringResource(R.string.online_loading_creating)
                } else {
                    stringResource(R.string.online_loading_joining, roomCode.orEmpty())
                },
            gameStarted = state == OnlineSession.State.Connecting,
        )
        return
    }
    val room = state as? OnlineSession.State.InRoom

    // Errors stay visible for a while, then go away so they don't cover the game.
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        visible = true
        if (state is OnlineSession.State.Failed || state is OnlineSession.State.Left) {
            delay(8_000)
            visible = false
        }
    }
    if (!visible) return

    val status = statusText(state, hasLinks, isPokemon, sameWorld)
    val onClick = if (room != null) onOpenRoom else null

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val game by GameViewport.rect.collectAsState()
        val space by GameViewport.area.collectAsState()
        val isLandscape = maxWidth > maxHeight
        when (val spot = findSpot(game, space, density, isLandscape)) {
            is Spot.Band ->
                with(density) {
                    // Portrait: centred in the free band above or below the game.
                    Box(
                        modifier =
                            Modifier
                                .offset { IntOffset(0, spot.top.roundToInt()) }
                                .fillMaxWidth()
                                .height(spot.height.toDp()),
                        contentAlignment = Alignment.Center,
                    ) {
                        StatusPill(status, Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp), onClick)
                    }
                }

            is Spot.Column ->
                with(density) {
                    // Landscape: in the column beside the game, under the L button.
                    Box(
                        modifier =
                            Modifier
                                .offset { IntOffset(spot.left.roundToInt(), spot.top.roundToInt()) }
                                .width(spot.width.toDp()),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        StatusPill(status, Modifier.padding(horizontal = 8.dp), onClick, narrow = true)
                    }
                }

            Spot.Corner -> {
                // No free space (or a layout that doesn't say): a small chip in the top left
                // corner, away from the menu button in the middle. Changes show in full for a
                // few seconds first.
                var expanded by remember { mutableStateOf(true) }
                LaunchedEffect(status) {
                    expanded = true
                    delay(6_000)
                    expanded = false
                }
                Box(
                    modifier =
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(start = 8.dp, top = if (isLandscape) 56.dp else 8.dp),
                ) {
                    if (expanded || room == null) {
                        StatusPill(status, Modifier.widthIn(max = 320.dp), onClick, narrow = true)
                    } else {
                        StatusChip(room, status, onClick)
                    }
                }
            }
        }
    }
}

/** What the status says right now. */
private data class Status(
    val title: String,
    // A shorter title for narrow spaces.
    val shortTitle: String = title,
    val subtitle: String? = null,
    val busy: Boolean = false,
    val linked: Boolean = false,
    val isError: Boolean = false,
)

@Composable
private fun statusText(
    state: OnlineSession.State,
    hasLinks: Boolean,
    isPokemon: Boolean,
    sameWorld: Boolean,
): Status =
    when (state) {
        is OnlineSession.State.InRoom -> {
            val title = stringResource(R.string.online_status_room, state.code, state.players.size, state.maxPlayers)
            val short = stringResource(R.string.online_status_room_short, state.code, state.players.size, state.maxPlayers)
            val partner = state.myGroup?.firstOrNull { it.id != state.clientId }?.let { member -> state.players.firstOrNull { it.id == member.id } }
            when {
                state.reconnecting -> Status(title, shortTitle = short, subtitle = stringResource(R.string.online_status_reconnecting), busy = true)
                state.sync == OnlineSession.Sync.WAITING_FOR_HOST -> Status(title, shortTitle = short, subtitle = stringResource(R.string.online_status_waiting_host), busy = true)
                state.sync == OnlineSession.Sync.WAITING_FOR_PLAYERS -> Status(title, shortTitle = short, subtitle = stringResource(R.string.online_status_waiting_sync), busy = true)
                state.players.size <= 1 -> Status(title, shortTitle = short, subtitle = stringResource(R.string.online_status_alone))
                hasLinks && partner?.away == true -> Status(title, shortTitle = short, subtitle = stringResource(R.string.online_status_partner_away, partner.name), busy = true)
                hasLinks && state.myGroup != null -> {
                    val names = state.linkedNames.joinToString(", ")
                    Status(
                        title,
                        shortTitle = short,
                        subtitle =
                            stringResource(
                                when {
                                    sameWorld -> R.string.online_status_linked_world
                                    isPokemon -> R.string.online_status_linked_pokemon
                                    else -> R.string.online_status_linked_with
                                },
                                names,
                            ),
                        linked = true,
                    )
                }
                hasLinks -> Status(title, shortTitle = short, subtitle = stringResource(R.string.online_status_tap_to_link))
                else -> Status(title, shortTitle = short, subtitle = state.players.joinToString(", ") { it.name })
            }
        }
        is OnlineSession.State.Failed -> Status(stringResource(errorMessage(state.reason)), isError = true)
        OnlineSession.State.Left -> Status(stringResource(R.string.online_status_left))
        OnlineSession.State.WaitingForGame -> Status(stringResource(R.string.online_status_waiting_game), busy = true)
        OnlineSession.State.Connecting -> Status(stringResource(R.string.online_status_connecting), busy = true)
    }

@Composable
private fun StatusPill(
    status: Status,
    modifier: Modifier,
    onClick: (() -> Unit)?,
    narrow: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(if (narrow) 14.dp else 22.dp),
        color = PILL,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = if (narrow) 10.dp else 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 10.dp),
        ) {
            StatusIcon(status)
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    if (narrow) status.shortTitle else status.title,
                    style = if (narrow) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status.isError) Color(0xFFFFB4AB) else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                status.subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = if (narrow) 5 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIcon(status: Status) {
    when {
        status.busy -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
        status.linked -> Icon(Icons.Filled.Link, contentDescription = null, tint = LINKED, modifier = Modifier.size(18.dp))
        status.isError -> Unit
        else -> Icon(Icons.Filled.People, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

/** Folded status: players and whether we are linked. */
@Composable
private fun StatusChip(
    room: OnlineSession.State.InRoom,
    status: Status,
    onClick: (() -> Unit)?,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = PILL,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusIcon(status)
            Text("${room.players.size}/${room.maxPlayers}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Shown while the game starts and the room connects, so players know to wait. */
@Composable
private fun LoadingCard(
    title: String,
    gameStarted: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1F1C2C),
            contentColor = Color.White,
            modifier = Modifier.padding(24.dp).widthIn(max = 360.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = LINKED)
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LoadingStep(stringResource(R.string.online_loading_step_game), done = gameStarted, active = !gameStarted)
                    LoadingStep(stringResource(R.string.online_loading_step_room), done = false, active = gameStarted)
                }
                Text(
                    stringResource(R.string.online_loading_wait),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFFD86B),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun LoadingStep(
    text: String,
    done: Boolean,
    active: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            done -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = LINKED, modifier = Modifier.size(18.dp))
            active -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
            else -> Icon(Icons.Outlined.Circle, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (done || active) Color.White else Color.White.copy(alpha = 0.55f),
        )
    }
}

/** Where the status goes, in pixels from the top left of the screen. */
private sealed interface Spot {
    /** Portrait: a full-width band of free space above or below the game. */
    data class Band(val top: Float, val height: Float) : Spot

    /** Landscape: the column beside the game. */
    data class Column(val left: Float, val top: Float, val width: Float) : Spot

    data object Corner : Spot
}

private fun findSpot(
    game: Rect?,
    space: Rect?,
    density: Density,
    isLandscape: Boolean,
): Spot {
    if (game == null || space == null || game.isEmpty) return Spot.Corner
    with(density) {
        if (!isLandscape) {
            val below = space.bottom - game.bottom
            val above = game.top - space.top
            return when {
                below >= BAND_MIN_HEIGHT.toPx() -> Spot.Band(game.bottom, below)
                above >= BAND_MIN_HEIGHT.toPx() -> Spot.Band(space.top, above)
                else -> Spot.Corner
            }
        }
        val beside = game.left - space.left
        return if (beside >= COLUMN_MIN_WIDTH.toPx()) {
            Spot.Column(space.left, space.top + COLUMN_TOP.toPx(), beside)
        } else {
            Spot.Corner
        }
    }
}

private val BAND_MIN_HEIGHT: Dp = 52.dp
private val COLUMN_MIN_WIDTH: Dp = 132.dp

// Below the L button.
private val COLUMN_TOP: Dp = 58.dp

private val LINKED = Color(0xFF7BE0A5)
private val PILL = Color(0xE6262333)

private val POKEMON = Regex("pok[eé]mon", RegexOption.IGNORE_CASE)

private fun noticeText(
    context: Context,
    event: OnlineSession.Event,
    isPokemon: Boolean,
): String =
    when (event) {
        is OnlineSession.Event.LinkDeclined -> context.getString(R.string.online_link_declined, event.name)
        is OnlineSession.Event.LinkError -> event.message.ifBlank { context.getString(R.string.online_error_server) }
        is OnlineSession.Event.PlayerJoined ->
            when {
                event.linkedWithUs && isPokemon -> context.getString(R.string.online_notice_joined_linked_pokemon, event.name)
                event.linkedWithUs -> context.getString(R.string.online_notice_joined_linked, event.name)
                else -> context.getString(R.string.online_notice_joined, event.name)
            }
        is OnlineSession.Event.PlayerLeft -> context.getString(R.string.online_notice_left, event.name)
        is OnlineSession.Event.PlayerAway -> context.getString(R.string.online_notice_away, event.name)
        is OnlineSession.Event.PlayerBack -> context.getString(R.string.online_notice_back, event.name)
        OnlineSession.Event.Reconnected -> context.getString(R.string.online_notice_reconnected)
        // Shown as a conversation in the game, not as a notice.
        is OnlineSession.Event.Invited, is OnlineSession.Event.InviteAnswered -> ""
    }

private fun errorMessage(reason: OnlineSession.Reason): Int =
    when (reason) {
        OnlineSession.Reason.NOT_SUPPORTED -> R.string.online_error_not_supported
        OnlineSession.Reason.CONNECTION_FAILED -> R.string.online_error_connection_failed
        OnlineSession.Reason.CONNECTION_LOST -> R.string.online_error_connection_lost
        OnlineSession.Reason.ROOM_NOT_FOUND -> R.string.online_error_room_not_found
        OnlineSession.Reason.ROOM_FULL -> R.string.online_error_room_full
        OnlineSession.Reason.VERSION_MISMATCH -> R.string.online_error_version
        OnlineSession.Reason.HOST_LEFT -> R.string.online_error_host_left
        OnlineSession.Reason.ROOM_CLOSED -> R.string.online_error_room_closed
        OnlineSession.Reason.REMOVED -> R.string.online_error_removed
        OnlineSession.Reason.SERVER_ERROR -> R.string.online_error_server
    }

private fun shareRoom(
    context: Context,
    serverUrl: String,
    code: String,
    gameTitle: String,
) {
    val link = OnlineSettings.inviteLink(serverUrl, code)
    val appName = context.getString(R.string.lemuroid_name)
    val text = context.getString(R.string.online_share_text, appName, code, link)
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, gameTitle)
            putExtra(Intent.EXTRA_TEXT, text)
        }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.online_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
