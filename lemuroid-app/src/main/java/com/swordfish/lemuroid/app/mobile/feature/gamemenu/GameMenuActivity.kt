@file:Suppress("UNUSED", "INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.swordfish.lemuroid.app.mobile.feature.gamemenu

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.coreoptions.GameMenuCoreOptionsScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.coreoptions.GameMenuCoreOptionsViewModel
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.states.GameMenuStatesScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.cheats.GameMenuCheatsScreen
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.states.GameMenuStatesViewModel
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.android.RetrogradeComponentActivity
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import java.security.InvalidParameterException
import javax.inject.Inject

class GameMenuActivity : RetrogradeComponentActivity() {
    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    data class GameMenuRequest(
        val coreOptions: List<LemuroidCoreOption>,
        val advancedCoreOptions: List<LemuroidCoreOption>,
        val game: Game,
        val coreConfig: SystemCoreConfig,
        val audioEnabled: Boolean,
        val fastForwardSupported: Boolean,
        val fastForwardEnabled: Boolean,
        val numDisks: Int,
        val currentDisk: Int,
        val currentTiltConfiguration: TiltConfiguration,
        val allTiltConfigurations: List<TiltConfiguration>,
        val cheatsSupported: Boolean,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT),
        )

        val extras = intent.extras

        val gameMenuRequest =
            GameMenuRequest(
                coreOptions =
                    intent.serializable<Array<LemuroidCoreOption>>(GameMenuContract.EXTRA_CORE_OPTIONS)
                        ?.toList()
                        ?: throw InvalidParameterException("Missing EXTRA_CORE_OPTIONS"),
                advancedCoreOptions =
                    intent.serializable<Array<LemuroidCoreOption>>(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS)
                        ?.toList()
                        ?: throw InvalidParameterException("Missing EXTRA_ADVANCED_CORE_OPTIONS"),
                game =
                    intent.serializable<Game>(GameMenuContract.EXTRA_GAME)
                        ?: throw InvalidParameterException("Missing EXTRA_GAME"),
                coreConfig =
                    intent.serializable<SystemCoreConfig>(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG)
                        ?: throw InvalidParameterException("Missing EXTRA_SYSTEM_CORE_CONFIG"),
                audioEnabled =
                    extras?.getBoolean(GameMenuContract.EXTRA_AUDIO_ENABLED, false) ?: false,
                fastForwardSupported =
                    extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED, false) ?: false,
                fastForwardEnabled =
                    extras?.getBoolean(GameMenuContract.EXTRA_FAST_FORWARD, false) ?: false,
                numDisks =
                    extras?.getInt(GameMenuContract.EXTRA_DISKS, 0) ?: 0,
                currentDisk =
                    extras?.getInt(GameMenuContract.EXTRA_CURRENT_DISK, 0) ?: 0,
                currentTiltConfiguration =
                    intent.serializable<TiltConfiguration>(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG)
                        ?: TiltConfiguration.Disabled,
                allTiltConfigurations =
                    intent.serializable<Array<TiltConfiguration>>(GameMenuContract.EXTRA_TILT_ALL_CONFIGS)
                        ?.toList()
                        ?: emptyList(),
                cheatsSupported = extras?.getBoolean(GameMenuContract.EXTRA_CHEATS_SUPPORTED, true) ?: true,
            )

        setContent {
            GameMenuScreen(gameMenuRequest)
        }
    }

    @Composable
    private fun GameMenuScreen(gameMenuRequest: GameMenuRequest) {
        AppTheme {
            // The menu card is always dark grey with white text, whatever the phone's colours.
            MaterialTheme(
                colorScheme =
                    MaterialTheme.colorScheme.copy(
                        surface = MENU_CARD,
                        background = MENU_CARD,
                        onSurface = ComposeColor.White,
                        onBackground = ComposeColor.White,
                        onSurfaceVariant = ComposeColor.White.copy(alpha = 0.6f),
                    ),
            ) {
                val navController = rememberNavController()
                val navBackStackEntry = navController.currentBackStackEntryAsState()
                val currentRoute =
                    navBackStackEntry.value?.destination?.route
                        ?.let { GameMenuRoute.findByRoute(it) }
                        ?: GameMenuRoute.HOME

                FloatingMenu(onDismiss = { finish() }) {
                    if (currentRoute.canGoBack()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 8.dp, end = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                            Text(
                                text = stringResource(currentRoute.titleId),
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                            )
                        }
                    }
                    NavHost(
                        modifier = Modifier.fillMaxWidth(),
                        navController = navController,
                        startDestination = GameMenuRoute.HOME.route,
                        enterTransition = { fadeIn() },
                        exitTransition = { fadeOut() },
                    ) {
                        composable(GameMenuRoute.HOME) {
                            GameMenuHomeScreen(navController, gameMenuRequest, ::onResult, onClose = { finish() })
                        }
                        composable(GameMenuRoute.SAVE) {
                            GameMenuStatesScreen(
                                viewModel(
                                    factory =
                                        GameMenuStatesViewModel.Factory(
                                            application,
                                            gameMenuRequest,
                                            statesManager,
                                            false,
                                            statesPreviewManager,
                                        ),
                                ),
                                onStateClicked = {
                                    onResult { putExtra(GameMenuContract.RESULT_SAVE, it) }
                                },
                            )
                        }
                        composable(GameMenuRoute.LOAD) {
                            GameMenuStatesScreen(
                                viewModel(
                                    factory =
                                        GameMenuStatesViewModel.Factory(
                                            application,
                                            gameMenuRequest,
                                            statesManager,
                                            true,
                                            statesPreviewManager,
                                        ),
                                ),
                                onStateClicked = {
                                    onResult { putExtra(GameMenuContract.RESULT_LOAD, it) }
                                },
                            )
                        }
                        composable(GameMenuRoute.CHEATS) {
                            GameMenuCheatsScreen(gameMenuRequest.game)
                        }
                        composable(GameMenuRoute.OPTIONS) {
                            GameMenuCoreOptionsScreen(
                                viewModel(
                                    factory = GameMenuCoreOptionsViewModel.Factory(inputDeviceManager),
                                ),
                                gameMenuRequest,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onResult(block: Intent.() -> Unit) {
        val resultIntent = Intent()
        resultIntent.block()
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    @dagger.Module
    abstract class Module
}

private val MENU_CARD = ComposeColor(0xFF202127)

/**
 * A rounded card in the middle of the screen over the dimmed game. Its content scrolls when it
 * doesn't fit, and a tap anywhere outside the card closes the menu.
 */
@Composable
fun FloatingMenu(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
        )
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val isLandscape = maxWidth > maxHeight
            Surface(
                modifier =
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(if (isLandscape) 0.62f else 0.9f)
                        .heightIn(max = maxHeight),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 8.dp,
            ) {
                Column(modifier = Modifier.animateContentSize().padding(vertical = 6.dp)) {
                    content()
                }
            }
        }
    }
}
