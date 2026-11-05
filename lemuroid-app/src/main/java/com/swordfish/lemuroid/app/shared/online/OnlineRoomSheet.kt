package com.swordfish.lemuroid.app.shared.online

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.online.world.NoWorld
import com.swordfish.lemuroid.app.shared.online.world.WorldSession
import com.swordfish.lemuroid.app.shared.online.world.Worlds

/**
 * Everyone in the room, and (in trade & battle rooms) who is linked with whom, with the steps
 * to trade and battle: the step the player is on is highlighted.
 */
@Composable
fun OnlineRoomSheet(
    room: OnlineSession.State.InRoom,
    hasLinks: Boolean,
    isPokemon: Boolean,
    onLink: (Int) -> Unit,
    onUnlink: () -> Unit,
    onInvite: () -> Unit,
    onDismiss: () -> Unit,
    world: WorldSession? = null,
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
            OnlineRoomSheetContent(
                room = room,
                hasLinks = hasLinks,
                isPokemon = isPokemon,
                onLink = onLink,
                onUnlink = onUnlink,
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                world = world,
            )
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

/** The steps, the players and the unplug button. */
@Composable
fun OnlineRoomSheetContent(
    room: OnlineSession.State.InRoom,
    hasLinks: Boolean,
    isPokemon: Boolean,
    onLink: (Int) -> Unit,
    onUnlink: () -> Unit,
    modifier: Modifier = Modifier,
    world: WorldSession? = null,
) {
    // Where everyone is changes as they walk: follow it while the panel is open.
    world?.view?.collectAsState()
    val visitingHost = world?.visit?.visiting?.collectAsState()?.value
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (visitingHost != null) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    stringResource(R.string.visit_panel, visitingHost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        if (hasLinks) {
            LinkSteps(room, isPokemon, sameWorld = world != null)
            if (world != null && world.adapter != NoWorld) {
                Text(
                    stringResource(R.string.online_world_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            room.players.forEach { player ->
                PlayerRow(room, player, hasLinks, onLink, world)
            }
        }
        if (hasLinks && room.myGroup != null) {
            OutlinedButton(onClick = onUnlink, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.online_unlink))
            }
        }
    }
}

/** 1. Invite a friend  2. Link up  3. Go to the link spot in the game  4. Trade or battle. */
@Composable
private fun LinkSteps(
    room: OnlineSession.State.InRoom,
    isPokemon: Boolean,
    sameWorld: Boolean,
) {
    val invited = room.players.size > 1
    val linked = room.myGroup != null
    val steps =
        buildList {
            add(stringResource(R.string.online_step_invite) to invited)
            if (sameWorld) {
                // Same world: meet on the map and talk; saying yes links the two of you.
                add(stringResource(R.string.online_step_meet) to linked)
                add(stringResource(R.string.online_step_talk) to false)
            } else {
                add(stringResource(R.string.online_step_link) to linked)
                add(stringResource(if (isPokemon) R.string.online_step_go_pokemon else R.string.online_step_go_other) to false)
                if (isPokemon) add(stringResource(R.string.online_step_play_pokemon) to false)
            }
        }
    // The first step not done yet is the one to do now.
    val current = steps.indexOfFirst { !it.second }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.online_steps_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        steps.forEachIndexed { index, (text, done) ->
            StepRow(number = index + 1, text = text, done = done, current = index == current)
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    text: String,
    done: Boolean,
    current: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = CircleShape,
            color =
                when {
                    done -> colors.primary
                    current -> colors.primaryContainer
                    else -> colors.surfaceVariant
                },
            contentColor =
                when {
                    done -> colors.onPrimary
                    current -> colors.onPrimaryContainer
                    else -> colors.onSurfaceVariant
                },
            modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (done) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                } else {
                    Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun PlayerRow(
    room: OnlineSession.State.InRoom,
    player: OnlineSession.Player,
    hasLinks: Boolean,
    onLink: (Int) -> Unit,
    world: WorldSession?,
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
            val status =
                when {
                    player.away -> stringResource(R.string.online_player_away)
                    !hasLinks -> null
                    isMe && group != null -> stringResource(R.string.online_status_linked_with, room.linkedNames.joinToString(", "))
                    linkedWithMe -> stringResource(R.string.online_player_linked_with_you)
                    group != null -> stringResource(R.string.online_player_linked)
                    else -> stringResource(R.string.online_player_not_linked)
                }
            if (status != null) {
                val highlight = (linkedWithMe || (isMe && group != null)) && !player.away
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (world != null && hasLinks && !player.away) {
                val where = if (isMe) ourPlaceText(world) else placeText(world.spotOf(player.id))
                Text(
                    where,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (hasLinks && !isMe && !linkedWithMe && !player.away) {
            val asked = room.outgoingRequest == player.id
            FilledTonalButton(onClick = { onLink(player.id) }, enabled = !asked) {
                Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(if (asked) R.string.online_link_asked else R.string.online_link))
            }
        }
    }
}

/** Our game and where we are. */
@Composable
private fun ourPlaceText(world: WorldSession): String {
    val game = Worlds.gameName(world.adapter.world) ?: return stringResource(R.string.online_game_not_shown)
    val place = world.ourPlace() ?: return game
    return stringResource(R.string.online_place_you, game, place)
}

/** Where another player is, and why they may not be on our screen. */
@Composable
private fun placeText(spot: WorldSession.Spot): String =
    when (spot) {
        is WorldSession.Spot.Here -> stringResource(R.string.online_place_here, spot.place ?: stringResource(R.string.online_place_this_map))
        is WorldSession.Spot.Nearby -> stringResource(R.string.online_place_nearby, spot.place ?: "")
        is WorldSession.Spot.Elsewhere ->
            spot.place?.let { stringResource(R.string.online_place_elsewhere, it) } ?: stringResource(R.string.online_place_somewhere)
        WorldSession.Spot.Busy -> stringResource(R.string.online_place_busy)
        is WorldSession.Spot.OtherGame ->
            spot.game?.let { stringResource(R.string.online_place_other_game, it) } ?: stringResource(R.string.online_place_other_game_unknown)
        WorldSession.Spot.NotShown -> stringResource(R.string.online_place_not_shown)
        WorldSession.Spot.Unknown -> stringResource(R.string.online_place_unknown)
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
