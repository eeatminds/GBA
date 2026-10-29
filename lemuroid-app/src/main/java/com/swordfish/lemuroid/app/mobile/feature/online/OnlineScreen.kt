package com.swordfish.lemuroid.app.mobile.feature.online

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.online.LinkMode
import com.swordfish.lemuroid.app.shared.online.RoomInfo
import com.swordfish.lemuroid.lib.library.db.entity.Game

private val Game.displayName: String get() = customName ?: title

@Composable
fun OnlineScreen(
    modifier: Modifier = Modifier,
    viewModel: OnlineViewModel,
    onHost: (game: Game, mode: LinkMode, isPublic: Boolean, playerName: String, serverUrl: String) -> Unit,
    onJoin: (game: Game, room: RoomInfo, playerName: String, serverUrl: String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text =
            when (message) {
                OnlineViewModel.Message.REPORT_SENT -> R.string.online_report_sent
                OnlineViewModel.Message.REPORT_FAILED -> R.string.online_report_failed
            }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { IntroCard() }

        if (!state.serverConfigured) {
            item { ServerMissingCard(onSetServer = { viewModel.showDialog(OnlineViewModel.Dialog.Server) }) }
            return@LazyColumn
        }

        item {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.playerName,
                onValueChange = viewModel::setPlayerName,
                label = { Text(stringResource(R.string.online_name_label)) },
                placeholder = { Text(stringResource(R.string.online_name_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.showDialog(OnlineViewModel.Dialog.Host) },
                ) { Text(stringResource(R.string.online_host)) }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.showDialog(OnlineViewModel.Dialog.JoinCode()) },
                ) { Text(stringResource(R.string.online_join_code)) }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.online_public_rooms),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.roomsStatus == OnlineViewModel.RoomsStatus.LOADING) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(14.dp))
                } else {
                    IconButton(onClick = viewModel::refreshRooms) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.online_refresh))
                    }
                }
            }
        }

        when {
            state.roomsStatus == OnlineViewModel.RoomsStatus.ERROR && state.publicRooms.isEmpty() ->
                item { HintText(stringResource(R.string.online_rooms_error)) }

            state.publicRooms.isEmpty() && state.roomsStatus != OnlineViewModel.RoomsStatus.LOADING ->
                item { HintText(stringResource(R.string.online_no_rooms)) }

            else ->
                items(state.publicRooms, key = { it.code }) { room ->
                    RoomItem(
                        room = room,
                        onJoin = { viewModel.showDialog(OnlineViewModel.Dialog.PickGame(room)) },
                        onReport = { viewModel.showDialog(OnlineViewModel.Dialog.Report(room)) },
                    )
                }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.online_server_label, state.serverUrl),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { viewModel.showDialog(OnlineViewModel.Dialog.Server) }) {
                    Text(stringResource(R.string.online_server_change))
                }
            }
        }
    }

    when (val dialog = state.dialog) {
        null -> Unit
        is OnlineViewModel.Dialog.Host ->
            HostDialog(
                games = state.gbaGames,
                onDismiss = { viewModel.showDialog(null) },
                onStart = { game, mode, isPublic ->
                    viewModel.showDialog(null)
                    onHost(game, mode, isPublic, state.playerName, state.serverUrl)
                },
            )

        is OnlineViewModel.Dialog.JoinCode ->
            JoinCodeDialog(
                dialog = dialog,
                onCodeChange = viewModel::updateJoinCode,
                onDismiss = { viewModel.showDialog(null) },
                onSubmit = { viewModel.lookupRoom(dialog.code) },
            )

        is OnlineViewModel.Dialog.PickGame ->
            PickGameDialog(
                room = dialog.room,
                games = state.gbaGames,
                onDismiss = { viewModel.showDialog(null) },
                onJoin = { game ->
                    viewModel.showDialog(null)
                    onJoin(game, dialog.room, state.playerName, state.serverUrl)
                },
            )

        is OnlineViewModel.Dialog.Server ->
            ServerDialog(
                currentUrl = state.serverUrl,
                onDismiss = { viewModel.showDialog(null) },
                onSave = viewModel::setServerUrl,
            )

        is OnlineViewModel.Dialog.Report ->
            ReportDialog(
                room = dialog.room,
                onDismiss = { viewModel.showDialog(null) },
                onSend = { reason -> viewModel.reportRoom(dialog.room, reason) },
            )
    }
}

@Composable
private fun IntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.online_intro_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.online_intro_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun ServerMissingCard(onSetServer: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.online_server_missing_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.online_server_missing_message), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onSetServer) { Text(stringResource(R.string.online_server_set)) }
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun RoomItem(
    room: RoomInfo,
    onJoin: () -> Unit,
    onReport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(room.game, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    text = stringResource(R.string.online_room_host, room.host) + " · " + stringResource(room.mode.titleId),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.online_room_players, room.players, room.maxPlayers),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    TextButton(onClick = onJoin) { Text(stringResource(R.string.online_join)) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.online_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.online_report)) },
                                onClick = {
                                    menuOpen = false
                                    onReport()
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun GamePicker(
    games: List<Game>,
    selected: Game?,
    onSelect: (Game) -> Unit,
) {
    if (games.isEmpty()) {
        HintText(stringResource(R.string.online_no_gba_games))
        return
    }
    Column(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        games.forEach { game ->
            OptionRow(selected = selected?.id == game.id, onClick = { onSelect(game) }) {
                Text(game.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun OptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun HostDialog(
    games: List<Game>,
    onDismiss: () -> Unit,
    onStart: (Game, LinkMode, Boolean) -> Unit,
) {
    var selectedGameId by rememberSaveable { mutableStateOf<Int?>(null) }
    var mode by rememberSaveable { mutableStateOf(LinkMode.LINK_CABLE) }
    var isPublic by rememberSaveable { mutableStateOf(true) }
    val selectedGame = games.firstOrNull { it.id == selectedGameId } ?: games.singleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_host_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.online_pick_game), style = MaterialTheme.typography.titleSmall)
                GamePicker(games, selectedGame) { selectedGameId = it.id }
                HorizontalDivider()
                Text(stringResource(R.string.online_link_type), style = MaterialTheme.typography.titleSmall)
                OnlineViewModel.HOST_MODES.forEach { option ->
                    OptionRow(selected = mode == option, onClick = { mode = option }) {
                        Column {
                            Text(stringResource(option.titleId))
                            Text(
                                stringResource(option.descriptionId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { isPublic = !isPublic },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.online_public_room))
                        Text(
                            stringResource(R.string.online_public_room_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isPublic, onCheckedChange = { isPublic = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedGame != null,
                onClick = { selectedGame?.let { onStart(it, mode, isPublic) } },
            ) { Text(stringResource(R.string.online_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.online_cancel)) }
        },
    )
}

@Composable
private fun JoinCodeDialog(
    dialog: OnlineViewModel.Dialog.JoinCode,
    onCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    val errorText =
        when (dialog.error) {
            null -> null
            OnlineViewModel.JoinError.INVALID_CODE -> stringResource(R.string.online_join_code_invalid)
            OnlineViewModel.JoinError.NOT_FOUND -> stringResource(R.string.online_room_not_found)
            OnlineViewModel.JoinError.FULL -> stringResource(R.string.online_room_full)
            OnlineViewModel.JoinError.NETWORK -> stringResource(R.string.online_rooms_error)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_join_dialog_title)) },
        text = {
            OutlinedTextField(
                value = dialog.code,
                onValueChange = onCodeChange,
                label = { Text(stringResource(R.string.online_join_code_label)) },
                singleLine = true,
                isError = errorText != null,
                supportingText = if (errorText != null) ({ Text(errorText) }) else null,
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Go,
                    ),
            )
        },
        confirmButton = {
            if (dialog.checking) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp).padding(end = 8.dp), strokeWidth = 2.dp)
            } else {
                TextButton(enabled = dialog.code.length == 6, onClick = onSubmit) {
                    Text(stringResource(R.string.online_join))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.online_cancel)) }
        },
    )
}

@Composable
private fun PickGameDialog(
    room: RoomInfo,
    games: List<Game>,
    onDismiss: () -> Unit,
    onJoin: (Game) -> Unit,
) {
    var selectedGameId by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedGame =
        games.firstOrNull { it.id == selectedGameId }
            ?: games.firstOrNull { it.displayName.equals(room.game, ignoreCase = true) }
            ?: games.singleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_join_room_title, room.code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.online_join_room_details,
                        room.game,
                        stringResource(room.mode.titleId),
                        room.players,
                        room.maxPlayers,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.online_join_pick_game),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GamePicker(games, selectedGame) { selectedGameId = it.id }
            }
        },
        confirmButton = {
            TextButton(enabled = selectedGame != null, onClick = { selectedGame?.let(onJoin) }) {
                Text(stringResource(R.string.online_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.online_cancel)) }
        },
    )
}

@Composable
private fun ServerDialog(
    currentUrl: String,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(currentUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_server_dialog_title)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                placeholder = { Text(stringResource(R.string.online_server_dialog_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(url) }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.online_server_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.online_cancel)) }
            }
        },
    )
}

@Composable
private fun ReportDialog(
    room: RoomInfo,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.online_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${room.game} · ${room.host}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = reason,
                    onValueChange = { reason = it.take(300) },
                    placeholder = { Text(stringResource(R.string.online_report_hint)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(reason) }) { Text(stringResource(R.string.online_report_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.online_cancel)) }
        },
    )
}
