package com.swordfish.lemuroid.app.shared.online

import android.app.Activity
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.game.BaseGameActivity
import com.swordfish.lemuroid.app.shared.game.GameProcessLock
import com.swordfish.lemuroid.app.shared.main.GameLaunchTaskHandler
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.lib.core.CoreUpdater
import com.swordfish.lemuroid.lib.core.CoresSelection
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Starts a game connected to an online room. */
class OnlineGameLauncher(
    private val coreUpdater: CoreUpdater,
    private val coresSelection: CoresSelection,
    private val gameLaunchTaskHandler: GameLaunchTaskHandler,
) {
    /** Trade & battle room for a GBA game. */
    fun hostLink(
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
                kind = RoomKind.LINK,
                mode = mode,
                isPublic = isPublic,
                gameTitle = game.displayName(),
                systemId = SystemID.GBA.dbname,
                coreName = OnlineConfig.gpspCoreConfig(mode).coreID.coreName,
                inputDelayFrames = 0,
                maxPlayers = mode.maxPlayers,
            )
        launch(activity, game, config) { OnlineConfig.gpspCoreConfig(mode) }
    }

    /** Play together room: everyone plays the host's game. */
    @OptIn(DelicateCoroutinesApi::class)
    fun hostNetplay(
        activity: Activity,
        game: Game,
        inputDelay: InputDelay,
        isPublic: Boolean,
        playerName: String,
        serverUrl: String,
    ) {
        GlobalScope.launch(Dispatchers.Main) {
            val system = GameSystem.findById(game.systemId)
            val coreConfig = coresSelection.getCoreConfigForSystem(system)
            val config =
                OnlineConfig(
                    serverUrl = serverUrl,
                    playerName = playerName,
                    isHost = true,
                    roomCode = null,
                    kind = RoomKind.NETPLAY,
                    mode = LinkMode.AUTO,
                    isPublic = isPublic,
                    gameTitle = game.displayName(),
                    systemId = game.systemId,
                    coreName = coreConfig.coreID.coreName,
                    inputDelayFrames = inputDelay.frames,
                    maxPlayers = NetplaySystems.maxPlayers(game.systemId),
                )
            launch(activity, game, config) { coreConfig }
        }
    }

    fun join(
        activity: Activity,
        game: Game,
        room: RoomInfo,
        playerName: String,
        serverUrl: String,
    ) {
        val coreConfig =
            when (room.kind) {
                RoomKind.LINK -> OnlineConfig.gpspCoreConfig(room.mode)
                RoomKind.NETPLAY -> OnlineConfig.netplayCoreConfig(room.system, room.core)
            }
        if (coreConfig == null) {
            activity.displayToast(R.string.online_error_core_missing)
            return
        }
        val config =
            OnlineConfig(
                serverUrl = serverUrl,
                playerName = playerName,
                isHost = false,
                roomCode = room.code,
                kind = room.kind,
                mode = room.mode,
                isPublic = room.isPublic,
                gameTitle = game.displayName(),
                systemId = room.system,
                coreName = coreConfig.coreID.coreName,
                inputDelayFrames = room.delay,
                maxPlayers = room.maxPlayers,
            )
        launch(activity, game, config) { coreConfig }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun launch(
        activity: Activity,
        game: Game,
        config: OnlineConfig,
        coreConfig: () -> SystemCoreConfig,
    ) {
        if (GameProcessLock.isHeldByAnotherProcess(activity.applicationContext)) {
            activity.displayToast(R.string.online_another_game_running)
            return
        }

        activity.displayToast(R.string.online_preparing)
        GlobalScope.launch(Dispatchers.Main) {
            val systemCoreConfig = coreConfig()
            // Make sure the room's core is installed before the game starts.
            withContext(Dispatchers.IO) {
                runCatching { coreUpdater.downloadCores(activity.applicationContext, listOf(systemCoreConfig.coreID)) }
                    .onFailure { Timber.e(it, "Could not install the core for the online room") }
            }
            gameLaunchTaskHandler.handleGameStart(activity.applicationContext)
            BaseGameActivity.launchGame(
                activity = activity,
                systemCoreConfig = systemCoreConfig,
                game = game,
                // A joining player's game is replaced by the host's, so skip loading their own state.
                loadSave = config.isHost || config.kind == RoomKind.LINK,
                useLeanback = false,
                onlineConfig = config,
            )
        }
    }

    private fun Game.displayName(): String = customName ?: title
}
