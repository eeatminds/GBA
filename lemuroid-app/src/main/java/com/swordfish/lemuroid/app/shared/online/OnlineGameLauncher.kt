package com.swordfish.lemuroid.app.shared.online

import android.app.Activity
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.GameProcessLock
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Starts a GBA game connected to an online room. */
class OnlineGameLauncher(
    private val coreUpdater: CoreUpdater,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
) {
    fun host(
        activity: Activity,
        game: Game,
        mode: LinkMode,
        isPublic: Boolean,
        playerName: String,
        serverUrl: String,
    ) {
        val config =
            OnlineConfig(
                serverUrl = serverUrl,
                playerName = playerName,
                isHost = true,
                roomCode = null,
                mode = mode,
                isPublic = isPublic,
                gameTitle = game.customName ?: game.title,
            )
        launch(activity, game, config)
    }

    fun join(
        activity: Activity,
        game: Game,
        room: RoomInfo,
        playerName: String,
        serverUrl: String,
    ) {
        val config =
            OnlineConfig(
                serverUrl = serverUrl,
                playerName = playerName,
                isHost = false,
                roomCode = room.code,
                mode = room.mode,
                isPublic = room.isPublic,
                gameTitle = game.customName ?: game.title,
            )
        launch(activity, game, config)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun launch(
        activity: Activity,
        game: Game,
        config: OnlineConfig,
    ) {
        if (GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
            activity.displayToast(R.string.online_another_game_running)
            return
        }

        activity.displayToast(R.string.online_preparing)
        GlobalScope.launch(Dispatchers.Main) {
            // gpSP is a small extra core. Make sure it is installed before the game starts.
            withContext(Dispatchers.IO) {
                runCatching { coreUpdater.downloadCores(activity.applicationContext, listOf(CoreID.GPSP)) }
                    .onFailure { Timber.e(it, "Could not install the online core") }
            }
            gameLaunchTaskHandler.handleGameStart(activity.applicationContext)
            BaseGameActivity.launchGame(
                activity = activity,
                systemCoreConfig = OnlineConfig.gpspCoreConfig(config.mode),
                game = game,
                loadSave = true,
                useLeanback = false,
                onlineConfig = config,
            )
        }
    }
}
