package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.alorma.compose.settings.storage.memory.rememberMemoryIntSettingState
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.game.ClassicScreenPosition
import com.swordfish.lemuroid.app.mobile.feature.game.ClassicScreenSettings
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.utils.android.settings.LemuroidSettingsList
import kotlin.reflect.KFunction1

/**
 * The in-game menu: a plain list. Rows that are settings show their current value on the right;
 * tapping them switches it.
 */
@Composable
fun GameMenuHomeScreen(
    navController: NavController,
    gameMenuRequest: GameMenuActivity.GameMenuRequest,
    onResult: KFunction1<Intent.() -> Unit, Unit>,
) {
    val on = stringResource(R.string.menu_value_on)
    val off = stringResource(R.string.menu_value_off)

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        if (gameMenuRequest.coreConfig.statesSupported) {
            GameMenuRow(stringResource(R.string.game_menu_load)) { navController.navigateToRoute(GameMenuRoute.LOAD) }
            GameMenuRow(stringResource(R.string.game_menu_save)) { navController.navigateToRoute(GameMenuRoute.SAVE) }
        }

        if (gameMenuRequest.fastForwardSupported) {
            val enabled = gameMenuRequest.fastForwardEnabled
            GameMenuRow(stringResource(R.string.game_menu_fast_forward), value = if (enabled) on else off) {
                onResult { putExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD, !enabled) }
            }
        }

        if (gameMenuRequest.cheatsSupported) {
            GameMenuRow(stringResource(R.string.game_menu_cheats)) { navController.navigateToRoute(GameMenuRoute.CHEATS) }
        }

        GameMenuRow(stringResource(R.string.game_menu_screenshot)) {
            onResult { putExtra(GameMenuContract.RESULT_SCREENSHOT, true) }
        }

        val audioEnabled = gameMenuRequest.audioEnabled
        GameMenuRow(stringResource(R.string.game_menu_sound), value = if (audioEnabled) on else off) {
            onResult { putExtra(GameMenuContract.RESULT_ENABLE_AUDIO, !audioEnabled) }
        }

        if (gameMenuRequest.game.systemId in HANDHELD_SYSTEMS) {
            // This moves the game behind the menu right away, so the menu stays open.
            val context = LocalContext.current
            var screen by remember { mutableStateOf(ClassicScreenSettings.read(context)) }
            GameMenuRow(
                stringResource(R.string.game_menu_screen_position),
                value =
                    stringResource(
                        if (screen.position == ClassicScreenPosition.MIDDLE) R.string.screen_position_middle else R.string.screen_position_top,
                    ),
            ) {
                val next = if (screen.position == ClassicScreenPosition.MIDDLE) ClassicScreenPosition.TOP else ClassicScreenPosition.MIDDLE
                ClassicScreenSettings.setPosition(context, next)
                screen = screen.copy(position = next)
            }
        }

        if (gameMenuRequest.numDisks > 1) {
            LemuroidSettingsList(
                title = { Text(text = stringResource(id = R.string.game_menu_change_disk_button)) },
                items = (1..gameMenuRequest.numDisks).map { stringResource(R.string.game_menu_change_disk_disk, it) },
                state = rememberMemoryIntSettingState(gameMenuRequest.currentDisk),
                onItemSelected = { index, _ ->
                    onResult { putExtra(GameMenuContract.RESULT_CHANGE_DISK, index) }
                },
            )
        }

        GameMenuRow(stringResource(R.string.game_menu_edit_touch_controls)) {
            onResult { putExtra(GameMenuContract.RESULT_EDIT_TOUCH_CONTROLS, true) }
        }

        GameMenuRow(stringResource(R.string.game_menu_restart)) {
            onResult { putExtra(GameMenuContract.RESULT_RESET, true) }
        }
        GameMenuRow(stringResource(R.string.game_menu_quit)) {
            onResult { putExtra(GameMenuContract.RESULT_QUIT, true) }
        }
    }
}

/** One plain row of the menu, with an optional current value on the right. */
@Composable
fun GameMenuRow(
    title: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 52.dp)
                .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (value != null) {
            Text(
                text = value,
                modifier = Modifier.padding(start = 16.dp),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

// Systems that use the handheld layout with screen size and position options.
private val HANDHELD_SYSTEMS = setOf("gb", "gbc", "gba")
