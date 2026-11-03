package com.swordfish.lemuroid.app.shared.game

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.mobile.feature.game.GameActivity
import com.swordfish.lemuroid.app.mobile.feature.game.GameService
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.mobile.shared.compose.ui.AppTheme
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.ImmersiveActivity
import com.swordfish.lemuroid.app.shared.cheats.CheatsManager
import com.swordfish.lemuroid.app.shared.coreoptions.CoreOption
import com.swordfish.lemuroid.app.shared.coreoptions.LemuroidCoreOption
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSideEffects
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.online.OnlineConfig
import com.swordfish.lemuroid.app.shared.online.OnlineSession
import com.swordfish.lemuroid.app.shared.online.OnlineSettings
import com.swordfish.lemuroid.app.shared.online.OnlineStatusBar
import com.swordfish.lemuroid.app.shared.online.RoomKind
import com.swordfish.lemuroid.app.shared.online.world.GameMemory
import com.swordfish.lemuroid.app.shared.online.world.GameViewport
import com.swordfish.lemuroid.app.shared.online.world.NoWorld
import com.swordfish.lemuroid.app.shared.online.world.TrainerNames
import com.swordfish.lemuroid.app.shared.online.world.VisitStore
import com.swordfish.lemuroid.app.shared.online.world.WorldAdapter
import com.swordfish.lemuroid.app.shared.online.world.WorldAdapters
import com.swordfish.lemuroid.app.shared.online.world.WorldOverlay
import com.swordfish.lemuroid.app.shared.online.world.WorldSession
import com.swordfish.lemuroid.app.shared.online.world.WorldVisit
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.tv.game.TVGameActivity
import com.swordfish.lemuroid.common.animationDuration
import com.swordfish.lemuroid.common.coroutines.launchOnState
import com.swordfish.lemuroid.common.displayToast
import com.swordfish.lemuroid.common.dump
import com.swordfish.lemuroid.common.graphics.takeScreenshot
import com.swordfish.lemuroid.common.kotlin.serializable
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.ExposedSetting
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.Variable
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import dagger.Lazy
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

@OptIn(DelicateCoroutinesApi::class)
abstract class BaseGameActivity : ImmersiveActivity() {
    protected lateinit var game: Game
    private lateinit var system: GameSystem
    protected lateinit var systemCoreConfig: SystemCoreConfig

    @Inject
    lateinit var settingsManager: SettingsManager

    @Inject
    lateinit var statesManager: StatesManager

    @Inject
    lateinit var savesManager: SavesManager

    @Inject
    lateinit var statesPreviewManager: StatesPreviewManager

    @Inject
    lateinit var coreVariablesManager: CoreVariablesManager

    @Inject
    lateinit var inputDeviceManager: InputDeviceManager

    @Inject
    lateinit var gameLoader: GameLoader

    @Inject
    lateinit var controllerConfigsManager: ControllerConfigsManager

    @Inject
    lateinit var rumbleManager: RumbleManager

    @Inject
    lateinit var sharedPreferences: Lazy<SharedPreferences>

    private lateinit var baseGameScreenViewModel: BaseGameScreenViewModel

    private val startGameTime = System.currentTimeMillis()

    // Set when the game was started from an online room.
    private var onlineConfig: OnlineConfig? = null
    private var onlineSession: OnlineSession? = null
    private val worldSession = mutableStateOf<WorldSession?>(null)

    // Visiting the host's world (guests in Same World rooms), or coming home from a visit.
    private var worldVisit: WorldVisit? = null

    // Cheats would make players' games differ in "play together" rooms.
    private val cheatsAllowed: Boolean
        get() = onlineConfig?.kind != RoomKind.NETPLAY
    private var finishTriggered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpExceptionsHandler()
        requestGameRefreshRate()
        GameService.startService(applicationContext, intent)
        game = intent.getSerializableExtra(EXTRA_GAME) as Game
        systemCoreConfig = intent.getSerializableExtra(EXTRA_SYSTEM_CORE_CONFIG) as SystemCoreConfig
        system = GameSystem.findById(game.systemId)

        val viewModel by viewModels<BaseGameScreenViewModel> {
            BaseGameScreenViewModel.Factory(
                applicationContext,
                game,
                settingsManager,
                inputDeviceManager,
                controllerConfigsManager,
                system,
                systemCoreConfig,
                sharedPreferences.get(),
                savesManager,
                statesManager,
                statesPreviewManager,
                coreVariablesManager,
                rumbleManager,
            )
        }

        baseGameScreenViewModel = viewModel

        lifecycle.addObserver(baseGameScreenViewModel)

        val onlineConfig = intent.getSerializableExtra(EXTRA_ONLINE_CONFIG) as? OnlineConfig
        this.onlineConfig = onlineConfig
        val session =
            onlineConfig?.let { config ->
                OnlineSession(config, lifecycleScope) { variables ->
                    // Used to plug the Game Boy emulator's link cable into the room.
                    val values = variables.map { (key, value) -> Variable(key, value) }.toTypedArray()
                    baseGameScreenViewModel.retroGameView.retroGameView?.updateVariables(*values)
                }
            }
        onlineSession = session
        if (onlineConfig != null && onlineConfig.kind == RoomKind.NETPLAY && !onlineConfig.isHost) {
            baseGameScreenViewModel.disableSavePersistence()
        }

        setContent {
            AppTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BaseGameScreen(viewModel = baseGameScreenViewModel) {
                        GameScreen(viewModel)
                    }
                    if (session != null) {
                        worldSession.value?.let { world ->
                            val room = session.state.collectAsState().value as? OnlineSession.State.InRoom
                            WorldOverlay(world, room?.players?.associate { it.id to it.name }.orEmpty())
                        }
                        OnlineStatusBar(
                            session = session,
                            gameTitle = game.customName ?: game.title,
                            world = worldSession.value,
                            aPresses = baseGameScreenViewModel.aButtonPresses,
                            menuKeys = baseGameScreenViewModel.menuKeys,
                            onCaptureInput = { baseGameScreenViewModel.capturingInput = it },
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            baseGameScreenViewModel.retroGameView.waitRetroGameViewInitialized()
            // The emulator only exists once the game has drawn its first frame.
            baseGameScreenViewModel.retroGameView.waitGLEvent<GLRetroView.GLRetroEvents.FrameRendered>()
            applyCheats()
            // Others see our trainer name from the game's save (instead of "Player 1").
            if (session != null && session.hasLinks) session.useTrainerName(readTrainerName())
            // The core registers its link interface while the game loads.
            session?.start()
            // Same world rooms also share the map, in games we know how to read.
            if (session != null && session.hasLinks && session.sameWorld) startWorld(session) else finishLeftoverVisit()
            // Auto-resume: a quiet snapshot every 30 seconds while the game is on screen.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(PERIODIC_SAVE_MS)
                    baseGameScreenViewModel.requestPeriodicSave()
                }
            }
        }

        lifecycleScope.launch {
            baseGameScreenViewModel.loadGame(
                applicationContext,
                game,
                systemCoreConfig,
                gameLoader,
                intent.getBooleanExtra(EXTRA_LOAD_SAVE, false),
            )
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    baseGameScreenViewModel.requestFinish()
                }
            },
        )

        initialiseFlows()
    }

    @Composable
    abstract fun GameScreen(viewModel: BaseGameScreenViewModel)

    private fun initialiseFlows() {
        launchOnState(Lifecycle.State.CREATED) {
            initializeViewModelsEffectsFlow()
        }
    }

    private fun setUpExceptionsHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            performUnexpectedErrorFinish(exception)
        }
    }

    private fun transformExposedSetting(
        exposedSetting: ExposedSetting,
        coreOptions: List<CoreOption>,
    ): LemuroidCoreOption? {
        return coreOptions
            .firstOrNull { it.variable.key == exposedSetting.key }
            ?.let { LemuroidCoreOption(exposedSetting, it) }
    }

    private fun displayOptionsDialog(
        currentTiltConfiguration: TiltConfiguration,
        tiltConfigurations: List<TiltConfiguration>,
    ) {
        if (baseGameScreenViewModel.loadingState.value) {
            return
        }

        val coreOptions = getCoreOptions()

        val options =
            systemCoreConfig.exposedSettings
                .mapNotNull { transformExposedSetting(it, coreOptions) }

        val advancedOptions =
            systemCoreConfig.exposedAdvancedSettings
                .mapNotNull { transformExposedSetting(it, coreOptions) }

        val intent =
            Intent(this, getDialogClass()).apply {
                this.putExtra(GameMenuContract.EXTRA_CORE_OPTIONS, options.toTypedArray())
                this.putExtra(GameMenuContract.EXTRA_ADVANCED_CORE_OPTIONS, advancedOptions.toTypedArray())
                this.putExtra(
                    GameMenuContract.EXTRA_CURRENT_DISK,
                    baseGameScreenViewModel.retroGameView.retroGameView?.getCurrentDisk() ?: 0,
                )
                this.putExtra(
                    GameMenuContract.EXTRA_DISKS,
                    baseGameScreenViewModel.retroGameView.retroGameView?.getAvailableDisks() ?: 0,
                )
                this.putExtra(GameMenuContract.EXTRA_GAME, game)
                this.putExtra(GameMenuContract.EXTRA_CHEATS_SUPPORTED, cheatsAllowed)
                this.putExtra(GameMenuContract.EXTRA_SYSTEM_CORE_CONFIG, systemCoreConfig)
                this.putExtra(
                    GameMenuContract.EXTRA_AUDIO_ENABLED,
                    baseGameScreenViewModel.retroGameView.retroGameView?.audioEnabled,
                )
                // Fast forward would make this player run ahead of the others in an online room.
                this.putExtra(
                    GameMenuContract.EXTRA_FAST_FORWARD_SUPPORTED,
                    system.fastForwardSupport && onlineSession == null,
                )
                this.putExtra(
                    GameMenuContract.EXTRA_FAST_FORWARD,
                    (baseGameScreenViewModel.retroGameView.retroGameView?.frameSpeed ?: 1) > 1,
                )
                this.putExtra(
                    GameMenuContract.EXTRA_FRAME_SPEED,
                    baseGameScreenViewModel.retroGameView.retroGameView?.frameSpeed ?: 1,
                )
                this.putExtra(GameMenuContract.EXTRA_CURRENT_TILT_CONFIG, currentTiltConfiguration)
                // TODO PADS... Make sure to avoid passing this if a physical pad is connected.
                this.putExtra(GameMenuContract.EXTRA_TILT_ALL_CONFIGS, tiltConfigurations.toTypedArray())
            }
        startActivityForResult(intent, DIALOG_REQUEST)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    protected abstract fun getDialogClass(): Class<out Activity>

    private fun getCoreOptions(): List<CoreOption> {
        return baseGameScreenViewModel.retroGameView.retroGameView?.getVariables()
            ?.mapNotNull {
                val coreOptionResult =
                    runCatching {
                        CoreOption.fromLibretroDroidVariable(it)
                    }
                coreOptionResult.getOrNull()
            } ?: listOf()
    }

    private suspend fun initializeViewModelsEffectsFlow() {
        baseGameScreenViewModel.getSideEffects()
            .collect {
                when (it) {
                    is GameViewModelSideEffects.UiEffect.ShowMenu ->
                        displayOptionsDialog(
                            it.currentTiltConfiguration,
                            it.tiltConfigurations,
                        )
                    is GameViewModelSideEffects.UiEffect.ShowToast -> displayToast(it.message)
                    is GameViewModelSideEffects.UiEffect.SuccessfulFinish -> performSuccessfulActivityFinish()
                    is GameViewModelSideEffects.UiEffect.FailureFinish -> performErrorFinish(it.message)
                    is GameViewModelSideEffects.UiEffect.SaveQuickSave -> performSaveQuickSave()
                    is GameViewModelSideEffects.UiEffect.LoadQuickSave -> performLoadQuickSave()
                    is GameViewModelSideEffects.UiEffect.ToggleFastForward -> performToggleFastForward()
                }
            }
    }

    private fun performSaveQuickSave() {
        baseGameScreenViewModel.saveQuickSave()
    }

    private fun performLoadQuickSave() {
        baseGameScreenViewModel.loadQuickSave()
    }

    private fun performToggleFastForward() {
        baseGameScreenViewModel.toggleFastForward()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // A box over the game (the talk box) has the pad: hat and stick move its choice.
        if (baseGameScreenViewModel.capturingInput) {
            val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_Y)
            baseGameScreenViewModel.captureControllerDirection(x, y)
            return true
        }
        val handled = baseGameScreenViewModel.sendMotionEvent(event)
        if (handled) {
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        // A box over the game (the talk box) has the buttons: presses go to it, not the game.
        if (baseGameScreenViewModel.capturingInput) {
            if (event.repeatCount == 0 && baseGameScreenViewModel.captureKey(keyCode)) return true
            if (keyCode in BOX_KEYS) return true
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A && event.repeatCount == 0) baseGameScreenViewModel.aButtonPresses.tryEmit(Unit)
        val handled = baseGameScreenViewModel.sendKeyEvent(keyCode, event)
        if (handled) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val handled = baseGameScreenViewModel.sendKeyEvent(keyCode, event)
        if (handled) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun performSuccessfulActivityFinish() {
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_SESSION_DURATION, System.currentTimeMillis() - startGameTime)
                putExtra(PLAY_GAME_RESULT_GAME, intent.getSerializableExtra(EXTRA_GAME))
                putExtra(PLAY_GAME_RESULT_LEANBACK, intent.getBooleanExtra(EXTRA_LEANBACK, false))
            }

        setResult(RESULT_OK, resultIntent)
        finishAndExitProcess()
    }

    private fun performUnexpectedErrorFinish(exception: Throwable) {
        Timber.e(exception, "Handling java exception in BaseGameActivity")
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_ERROR, exception.message)
            }

        setResult(RESULT_UNEXPECTED_ERROR, resultIntent)
        finishAndExitProcess()
    }

    private fun performErrorFinish(message: String) {
        val resultIntent =
            Intent().apply {
                putExtra(PLAY_GAME_RESULT_ERROR, message)
            }

        setResult(RESULT_ERROR, resultIntent)
        finishAndExitProcess()
    }

    private fun finishAndExitProcess() {
        onFinishTriggered()
        GlobalScope.launch {
            delay(animationDuration().toLong())
            GameService.requestTermination()
        }
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    open fun onFinishTriggered() {
        finishTriggered = true
    }

    override fun onStart() {
        super.onStart()
        // Back from another app: if the room connection dropped meanwhile, get it back now.
        onlineSession?.onForeground()
    }

    override fun onStop() {
        if (!finishTriggered && !isFinishing && !isChangingConfigurations) {
            baseGameScreenViewModel.requestBackgroundSave()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            worldVisit?.stop()
            worldSession.value?.stop()
            GameViewport.rect.value = null
            GameViewport.area.value = null
            // Remember the room for a while, so the Online tab can offer to go back in.
            val session = onlineSession
            val code = session?.roomCode
            if (session != null && session.hasLinks && code != null && session.state.value is OnlineSession.State.InRoom) {
                OnlineSettings(applicationContext).rememberRoom(code)
            }
            session?.close()
            GameService.requestTermination()
        }
        super.onDestroy()
    }

    /**
     * The games run at about 60 frames a second. A screen running faster (90 or 120 Hz, common on
     * high-end phones) only shows each picture twice while using more power, so ask for 60 Hz
     * while the game is on screen. The phone goes back to its usual rate when the game closes.
     */
    private fun requestGameRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val current = display.mode
            if (current.refreshRate <= MAX_GAME_REFRESH_RATE) return
            val mode =
                display.supportedModes
                    .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
                    .filter { it.refreshRate in 59f..MAX_GAME_REFRESH_RATE }
                    .minByOrNull { kotlin.math.abs(it.refreshRate - 60f) }
                    ?: return
            window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        }.onFailure { Timber.w(it, "Could not ask for a 60 Hz screen") }
    }

    /** See the other players in our game: their characters are drawn over the game picture. */
    private fun startWorld(session: OnlineSession) {
        lifecycleScope.launch {
            // Games we can't show on maps still tell the others so (for the Players panel).
            val rom = gameRom()
            val adapter = rom?.let { WorldAdapters.forRom(game.systemId, it) } ?: NoWorld
            if (adapter == NoWorld) Timber.i("Same world: ${game.title} can't be shown on maps")
            val world =
                WorldSession(adapter, { GameMemory.ofRunningGame() }, session::sendPresence, lifecycleScope) { block ->
                    // Game memory is written between frames, on the emulation thread.
                    baseGameScreenViewModel.retroGameView.retroGameView?.queueEvent(block)
                }
            session.onPresence = world::onPresence
            session.onPlayerLeft = world::onPlayerLeft
            worldSession.value = world
            world.start()
            if (adapter != NoWorld) startVisit(session, world, adapter)
        }
    }

    // The game's ROM, read once (for the world readers and the trainer name).
    private var romBytes: ByteArray? = null

    private suspend fun gameRom(): ByteArray? = romBytes ?: WorldAdapters.loadRom(applicationContext, game)?.also { romBytes = it }

    private suspend fun readTrainerName(): String? =
        runCatching {
            val rom = gameRom() ?: return null
            val view = baseGameScreenViewModel.retroGameView.retroGameView ?: return null
            val save = withContext(Dispatchers.IO) { view.serializeSRAM() }
            TrainerNames.fromSave(game.systemId, rom, save)
        }.onFailure { Timber.w(it, "Could not read the trainer name") }.getOrNull()

    private fun visitStore() = VisitStore(File(filesDir, "visits/${game.id}"))

    private fun newVisit(adapter: WorldAdapter): WorldVisit =
        WorldVisit(adapter, { GameMemory.ofRunningGame() }, RunningGame(), visitStore(), lifecycleScope, ::showVisitNotice)
            .also { visit ->
                worldVisit = visit
                baseGameScreenViewModel.beforeFinish = { visit.goHome() }
            }

    /**
     * Guests join the host's world: once the host is on a map in the same game world, we are
     * taken next to them. Closing the game or the room ending brings us home.
     */
    private fun startVisit(
        session: OnlineSession,
        world: WorldSession,
        adapter: WorldAdapter,
    ) {
        val visit = newVisit(adapter)
        world.visit = visit
        lifecycleScope.launch {
            // Let the auto-resume snapshot load first: a visit must start from the real game.
            delay(VISIT_START_DELAY_MS)
            val first = session.state.first { it is OnlineSession.State.InRoom || it is OnlineSession.State.Failed || it is OnlineSession.State.Left }
            val room = first as? OnlineSession.State.InRoom
            visit.resume(room?.code?.takeIf { room.clientId != room.ownerId })
            launch {
                // The room is gone for good: back home.
                session.state.first { it is OnlineSession.State.Failed || it is OnlineSession.State.Left }
                visit.goHome()
            }
            if (room == null || room.clientId == room.ownerId) return@launch
            combine(world.view, session.state) { view, state -> view to state }.collect { (view, state) ->
                val inRoom = state as? OnlineSession.State.InRoom ?: return@collect
                if (inRoom.reconnecting) return@collect
                val hostId = inRoom.ownerId
                if (inRoom.players.none { it.id == hostId && !it.away }) return@collect
                visit.maybeJoin(
                    room = inRoom.code,
                    host = inRoom.playerName(hostId),
                    hostWorld = view.presences[hostId]?.world,
                    hostAvatar = view.others[hostId]?.current,
                    local = view.local,
                )
            }
        }
    }

    // A visit left from before (the app was closed during it): come home now.
    private fun finishLeftoverVisit() {
        val store = visitStore()
        if (!store.exists()) return
        lifecycleScope.launch {
            val rom = gameRom() ?: return@launch
            val adapter = WorldAdapters.forRom(game.systemId, rom) ?: return@launch
            delay(VISIT_START_DELAY_MS)
            newVisit(adapter).resume(null)
        }
    }

    private fun showVisitNotice(notice: WorldVisit.Notice) {
        val text =
            when (notice) {
                is WorldVisit.Notice.Joined -> getString(R.string.visit_joined, notice.host)
                is WorldVisit.Notice.OtherGame ->
                    notice.game?.let { getString(R.string.visit_other_game, notice.host, it) }
                        ?: getString(R.string.visit_other_game_unknown, notice.host)
                is WorldVisit.Notice.Home -> getString(if (notice.traded) R.string.visit_home_traded else R.string.visit_home)
                WorldVisit.Notice.HomeKeptAll -> getString(R.string.visit_home_kept_all)
            }
        runOnUiThread { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    /** The running emulator, for visits: snapshots and in-game saves between two frames. */
    private inner class RunningGame : WorldVisit.Emulator {
        override suspend fun <T> betweenFrames(block: (WorldVisit.Core) -> T): T {
            val view = baseGameScreenViewModel.retroGameView.retroGameView ?: error("The game isn't running")
            val core =
                object : WorldVisit.Core {
                    override fun saveState(): ByteArray = view.serializeState(false)

                    override fun loadState(state: ByteArray): Boolean = view.unserializeState(state, false)

                    override fun saveSram(): ByteArray = view.serializeSRAM(false)

                    override fun loadSram(sram: ByteArray): Boolean = view.unserializeSRAM(sram, false)
                }
            val result = CompletableDeferred<T>()
            view.queueEvent {
                runCatching { block(core) }
                    .onSuccess { result.complete(it) }
                    .onFailure { result.completeExceptionally(it) }
            }
            return result.await()
        }

        override suspend fun persist() = baseGameScreenViewModel.saveNow()
    }

    private fun applyCheats() {
        if (!cheatsAllowed) return
        lifecycleScope.launch(Dispatchers.IO) {
            val view = baseGameScreenViewModel.retroGameView.retroGameView ?: return@launch
            val cheats = CheatsManager(applicationContext).getCheats(game).filter { it.enabled }
            runCatching {
                view.resetCheats()
                cheats.forEachIndexed { index, cheat -> view.setCheat(index, true, cheat.coreCode) }
            }.onFailure { Timber.e(it, "Could not apply cheats") }
        }
    }

    private fun takeScreenshot() {
        lifecycleScope.launch {
            // Let the menu close so it is not in the picture.
            delay(400)
            val view = baseGameScreenViewModel.retroGameView.retroGameView ?: return@launch
            val bitmap =
                view.captureGameFrame()?.let { GameFrames.scaleToFit(it, SCREENSHOT_MAX_SIZE) }
                    ?: view.takeScreenshot(SCREENSHOT_MAX_SIZE, 3)
            val saved =
                bitmap != null &&
                    ScreenshotSaver.save(
                        applicationContext,
                        bitmap,
                        game.customName ?: game.title,
                        getString(R.string.lemuroid_name),
                    )
            displayToast(if (saved) R.string.screenshot_saved else R.string.screenshot_failed)
        }
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DIALOG_REQUEST) {
            Timber.i("Game menu dialog response: ${data?.extras.dump()}")
            // Cheats may have been changed in the menu.
            applyCheats()
            if (data?.getBooleanExtra(GameMenuContract.RESULT_SCREENSHOT, false) == true) {
                takeScreenshot()
            }
            if (data?.getBooleanExtra(GameMenuContract.RESULT_RESET, false) == true) {
                GlobalScope.launch {
                    baseGameScreenViewModel.reset()
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_SAVE) == true) {
                GlobalScope.launch {
                    baseGameScreenViewModel.saveSlot(data.getIntExtra(GameMenuContract.RESULT_SAVE, 0))
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_LOAD) == true) {
                GlobalScope.launch {
                    baseGameScreenViewModel.loadSlot(data.getIntExtra(GameMenuContract.RESULT_LOAD, 0))
                }
            }
            if (data?.getBooleanExtra(GameMenuContract.RESULT_QUIT, false) == true) {
                baseGameScreenViewModel.requestFinish()
            }
            if (data?.hasExtra(GameMenuContract.RESULT_CHANGE_DISK) == true) {
                val index = data.getIntExtra(GameMenuContract.RESULT_CHANGE_DISK, 0)
                baseGameScreenViewModel.retroGameView.retroGameView?.changeDisk(index)
            }
            if (data?.hasExtra(GameMenuContract.RESULT_ENABLE_AUDIO) == true) {
                baseGameScreenViewModel.retroGameView.retroGameView?.apply {
                    this.audioEnabled =
                        data.getBooleanExtra(
                            GameMenuContract.RESULT_ENABLE_AUDIO,
                            true,
                        )
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_ENABLE_FAST_FORWARD) == true) {
                baseGameScreenViewModel.retroGameView.retroGameView?.apply {
                    val fastForwardEnabled =
                        data.getBooleanExtra(
                            GameMenuContract.RESULT_ENABLE_FAST_FORWARD,
                            false,
                        )
                    this.frameSpeed = if (fastForwardEnabled) 2 else 1
                }
            }
            if (data?.hasExtra(GameMenuContract.RESULT_FRAME_SPEED) == true) {
                baseGameScreenViewModel.setFrameSpeed(data.getIntExtra(GameMenuContract.RESULT_FRAME_SPEED, 1))
            }
            if (data?.getBooleanExtra(GameMenuContract.RESULT_EDIT_TOUCH_CONTROLS, false) == true) {
                baseGameScreenViewModel.showEditControls(true)
            }
            if (data?.hasExtra(GameMenuContract.RESULT_CHANGE_TILT_CONFIG) == true) {
                val tiltConfig = data.serializable<TiltConfiguration>(GameMenuContract.RESULT_CHANGE_TILT_CONFIG)
                baseGameScreenViewModel.changeTiltConfiguration(tiltConfig!!)
            }
        }
    }

    companion object {
        const val DIALOG_REQUEST = 100

        // How often the auto-resume snapshot is saved while playing.
        private const val PERIODIC_SAVE_MS = 30_000L

        // Keys a box over the game uses, including repeats of held ones.
        private val BOX_KEYS =
            setOf(
                KeyEvent.KEYCODE_BUTTON_A,
                KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT,
            )

        // The auto-resume snapshot loads right after the first frame; visits start after it.
        private const val VISIT_START_DELAY_MS = 2_500L

        // Screens up to this rate already suit 60 frames a second games.
        private const val MAX_GAME_REFRESH_RATE = 65f

        private const val EXTRA_GAME = "GAME"
        private const val EXTRA_LOAD_SAVE = "LOAD_SAVE"
        private const val EXTRA_LEANBACK = "LEANBACK"
        private const val EXTRA_SYSTEM_CORE_CONFIG = "EXTRA_SYSTEM_CORE_CONFIG"
        private const val EXTRA_ONLINE_CONFIG = "EXTRA_ONLINE_CONFIG"
        private const val SCREENSHOT_MAX_SIZE = 1280

        const val REQUEST_PLAY_GAME = 1001
        const val PLAY_GAME_RESULT_SESSION_DURATION = "PLAY_GAME_RESULT_SESSION_DURATION"
        const val PLAY_GAME_RESULT_GAME = "PLAY_GAME_RESULT_GAME"
        const val PLAY_GAME_RESULT_LEANBACK = "PLAY_GAME_RESULT_LEANBACK"
        const val PLAY_GAME_RESULT_ERROR = "PLAY_GAME_RESULT_ERROR"

        const val RESULT_ERROR = Activity.RESULT_FIRST_USER + 2
        const val RESULT_UNEXPECTED_ERROR = Activity.RESULT_FIRST_USER + 3

        fun launchGame(
            activity: Activity,
            systemCoreConfig: SystemCoreConfig,
            game: Game,
            loadSave: Boolean,
            useLeanback: Boolean,
            onlineConfig: OnlineConfig? = null,
        ) {
            val gameActivity =
                if (useLeanback) {
                    TVGameActivity::class.java
                } else {
                    GameActivity::class.java
                }
            activity.startActivityForResult(
                Intent(activity, gameActivity).apply {
                    putExtra(EXTRA_GAME, game)
                    putExtra(EXTRA_LOAD_SAVE, loadSave)
                    putExtra(EXTRA_LEANBACK, useLeanback)
                    putExtra(EXTRA_SYSTEM_CORE_CONFIG, systemCoreConfig)
                    onlineConfig?.let { putExtra(EXTRA_ONLINE_CONFIG, it) }
                },
                REQUEST_PLAY_GAME,
            )
            activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
