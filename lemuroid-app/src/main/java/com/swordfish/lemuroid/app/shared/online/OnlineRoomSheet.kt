package com.swordfish.lemuroid.app.shared.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R

/** Everyone in the room, and (in trade & battle rooms) who is linked with whom. */
@Composable
fun OnlineRoomSheet(
    room: OnlineSession.State.InRoom,
    hasLinks: Boolean,
    onLink: (Int) -> Unit,
    onUnlink: () -> Unit,
    onInvite: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(stringResource(R.string.online_room_sheet_title, room.code))
                Text(
                    stringResource(R.string.online_room_sheet_players, room.players.size, room.maxPlayers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasLinks) {
                    Text(
                        stringResource(R.string.online_room_sheet_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    room.players.forEach { player ->
                        PlayerRow(room, player, hasLinks, onLink)
                    }
                }
                if (hasLinks && room.myGroup != null) {
                    HorizontalDivider()
                    OutlinedButton(onClick = onUnlink, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.online_unlink))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInvite) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.online_invite))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun PlayerRow(
    room: OnlineSession.State.InRoom,
    player: OnlineSession.Player,
    hasLinks: Boolean,
    onLink: (Int) -> Unit,
) {
    val isMe = player.id == room.clientId
    val group = room.groupOf(player.id)
    val linkedWithMe = !isMe && group != null && group == room.myGroup
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isMe) stringResource(R.string.online_player_you, player.name) else player.name,
                fontWeight = if (isMe) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hasLinks) {
                val status =
                    when {
                        linkedWithMe -> R.string.online_player_linked_with_you
                        group != null -> R.string.online_player_linked
                        else -> R.string.online_player_not_linked
                    }
                Text(
                    stringResource(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (linkedWithMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (hasLinks && !isMe && !linkedWithMe) {
            val asked = room.outgoingRequest == player.id
            FilledTonalButton(onClick = { onLink(player.id) }, enabled = !asked) {
                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(if (asked) R.string.online_link_asked else R.string.online_link))
            }
        }
    }
}

/** Someone wants to plug their link cable into ours. */
@Composable
fun LinkRequestDialog(
    player: OnlineSession.Player,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text(stringResource(R.string.online_link_request_title, player.name)) },
        text = { Text(stringResource(R.string.online_link_request_message)) },
        confirmButton = { TextButton(onClick = onAccept) { Text(stringResource(R.string.online_link_accept)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.online_link_decline)) } },
    )
}
