package com.swordfish.lemuroid.app.mobile.feature.home

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.LemuroidGameCard
import com.swordfish.lemuroid.app.utils.android.ComposableLifecycle
import com.swordfish.lemuroid.common.displayDetailsSettingsScreen
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.db.entity.Game

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel,
    onGameClick: (Game) -> Unit,
    onGameLongClick: (Game) -> Unit,
    onOpenCoreSelection: () -> Unit,
) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext

    ComposableLifecycle { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            viewModel.updatePermissions(applicationContext)
        }
    }

    val permissionsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                context.displayDetailsSettingsScreen()
            }
        }

    val state by viewModel.getViewStates().collectAsState(HomeViewModel.UIState())
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    LazyVerticalGrid(
        modifier = modifier.fillMaxSize(),
        // Phones show 3 columns in portrait; tablets and landscape fit more.
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.showNoNotificationPermissionCard) {
            fullWidth {
                HomeNotification(
                    titleId = R.string.home_notification_title,
                    messageId = R.string.home_notification_message,
                    actionId = R.string.home_notification_action,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }
        if (state.showNoGamesCard) {
            fullWidth {
                HomeNotification(
                    titleId = R.string.home_empty_title,
                    messageId = R.string.home_empty_message,
                    actionId = R.string.home_empty_action,
                    enabled = !state.indexInProgress,
                    onAction = { viewModel.addGames(context) },
                )
            }
        }
        if (state.showNoMicrophonePermissionCard) {
            fullWidth {
                HomeNotification(
                    titleId = R.string.home_microphone_title,
                    messageId = R.string.home_microphone_message,
                    actionId = R.string.home_microphone_action,
                    onAction = { permissionsLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
        if (state.showDesmumeDeprecatedCard) {
            fullWidth {
                HomeNotification(
                    titleId = R.string.home_notification_desmume_deprecated_title,
                    messageId = R.string.home_notification_desmume_deprecated_message,
                    actionId = R.string.home_notification_desmume_deprecated_action,
                    onAction = onOpenCoreSelection,
                )
            }
        }

        if (!state.hasGames) return@LazyVerticalGrid

        fullWidth {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = {
                    query = it
                    viewModel.setSearchQuery(it)
                },
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                query = ""
                                viewModel.setSearchQuery("")
                                focusManager.clearFocus()
                            },
                        ) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.home_clear_search))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = MaterialTheme.shapes.extraLarge,
            )
        }

        if (state.systemIds.size > 1) {
            fullWidth {
                SystemFilterChips(
                    systemIds = state.systemIds,
                    selected = state.selectedSystemId,
                    onSelect = viewModel::selectSystem,
                )
            }
        }

        if (!state.isFiltering && state.recentGames.isNotEmpty()) {
            fullWidth { SectionTitle(stringResource(R.string.home_continue_playing)) }
            fullWidth {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.recentGames, key = { "recent-${it.id}" }) { game ->
                        LemuroidGameCard(
                            modifier = Modifier.width(120.dp),
                            game = game,
                            onClick = { onGameClick(game) },
                            onLongClick = { onGameLongClick(game) },
                        )
                    }
                }
            }
        }

        fullWidth { SectionTitle("${stringResource(R.string.home_my_games)} (${state.games.size})") }

        if (state.games.isEmpty()) {
            fullWidth {
                Text(
                    text = stringResource(R.string.home_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.games, key = { it.id }) { game ->
            LemuroidGameCard(
                game = game,
                onClick = { onGameClick(game) },
                onLongClick = { onGameLongClick(game) },
            )
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun SystemFilterChips(
    systemIds: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val labeled =
        systemIds.mapNotNull { id ->
            val system = runCatching { GameSystem.findById(id) }.getOrNull() ?: return@mapNotNull null
            id to stringResource(system.shortTitleResId)
        }.sortedBy { it.second }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.home_all_consoles)) },
            )
        }
        items(labeled, key = { it.first }) { (id, label) ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(if (selected == id) null else id) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun HomeNotification(
    titleId: Int,
    messageId: Int,
    actionId: Int,
    enabled: Boolean = true,
    onAction: () -> Unit = { },
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(titleId), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(messageId), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(
                modifier = Modifier.align(Alignment.End),
                onClick = onAction,
                enabled = enabled,
            ) {
                Text(stringResource(id = actionId))
            }
        }
    }
}
