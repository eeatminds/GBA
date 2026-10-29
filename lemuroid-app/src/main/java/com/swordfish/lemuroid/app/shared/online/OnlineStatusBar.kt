package com.swordfish.lemuroid.app.shared.online

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import kotlinx.coroutines.delay

/** Small status pill shown over the game while it is connected to an online room. */
@Composable
fun OnlineStatusBar(
    session: OnlineSession,
    gameTitle: String,
) {
    val state by session.state.collectAsState()
    val context = LocalContext.current

    // Errors stay visible for a while, then the pill hides so it does not cover the game.
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        visible = true
        if (state is OnlineSession.State.Failed || state is OnlineSession.State.Left) {
            delay(8_000)
            visible = false
        }
    }
    if (!visible) return

    Box(
        modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = Color.Black.copy(alpha = 0.65f),
            contentColor = Color.White,
            modifier =
                Modifier
                    .widthIn(max = 420.dp)
                    .clickable(enabled = state is OnlineSession.State.InRoom) {
                        val room = state as? OnlineSession.State.InRoom ?: return@clickable
                        shareRoom(context, session.serverUrl, room.code, gameTitle)
                    },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (val current = state) {
                    OnlineSession.State.WaitingForGame, OnlineSession.State.Connecting -> {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Text(
                            stringResource(
                                if (current == OnlineSession.State.Connecting) {
                                    R.string.online_status_connecting
                                } else {
                                    R.string.online_status_waiting_game
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    is OnlineSession.State.InRoom -> {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                stringResource(R.string.online_status_room, current.code, current.players.size, current.maxPlayers),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            val subtitle =
                                when {
                                    current.sync == OnlineSession.Sync.WAITING_FOR_HOST ->
                                        stringResource(R.string.online_status_waiting_host)
                                    current.sync == OnlineSession.Sync.WAITING_FOR_PLAYERS ->
                                        stringResource(R.string.online_status_waiting_sync)
                                    current.players.size <= 1 -> stringResource(R.string.online_status_waiting_players)
                                    else -> current.players.joinToString(", ") { it.name }
                                }
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(R.string.online_status_share),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    is OnlineSession.State.Failed -> {
                        Text(stringResource(errorMessage(current.reason)), style = MaterialTheme.typography.labelLarge)
                    }

                    OnlineSession.State.Left -> {
                        Text(stringResource(R.string.online_status_left), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
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
