package com.swordfish.lemuroid.app.shared.game

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.swordfish.lemuroid.app.mobile.feature.game.GameService
import com.swordfish.lemuroid.app.mobile.feature.settings.SettingsManager
import com.swordfish.lemuroid.app.shared.GameMenuContract
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelInput
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelRetroGameView
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSaves
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelSideEffects
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTilt
import com.swordfish.lemuroid.app.shared.game.viewmodel.GameViewModelTouchControls
import com.swordfish.lemuroid.app.shared.input.InputDeviceManager
import com.swordfish.lemuroid.app.shared.rumble.RumbleManager
import com.swordfish.lemuroid.app.shared.settings.ControllerConfigsManager
import com.swordfish.lemuroid.app.shared.settings.HapticFeedbackMode
import com.swordfish.lemuroid.common.longAnimationDuration
import com.swordfish.lemuroid.lib.controller.ControllerConfig
import com.swordfish.lemuroid.lib.core.CoreVariablesManager
import com.swordfish.lemuroid.lib.game.GameLoader
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.saves.SavesManager
import com.swordfish.lemuroid.lib.saves.StatesManager
import com.swordfish.lemuroid.lib.saves.StatesPreviewManager
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.touchinput.radial.sensors.TiltConfiguration
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.inputevents.InputEvent
import gg.padkit.inputstate.InputState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class BaseGameScreenViewModel(
    private val appContext: Context,
    game: Game,
    settingsManager: SettingsManager,
    inputDeviceManager: InputDeviceManager,
    controllerConfigsManager: ControllerConfigsManager,
    system: GameSystem,
    systemCoreConfig: SystemCoreConfig,
    sharedPreferences: SharedPreferences,
    savesManager: SavesManager,
    statesManager: StatesManager,
    statesPreviewManager: StatesPreviewManager,
    coreVariablesManager: CoreVariablesManager,
    rumbleManager: RumbleManager,
) : ViewModel(), DefaultLifecycleObserver {
    class Factory(
        private val appContext: Context,
        private val game: Game,
        private val settingsManager: SettingsManager,
        private val inputDeviceManager: InputDeviceManager,
        private val controllerConfigsManager: ControllerConfigsManager,
        private val system: GameSystem,
        private val systemCoreConfig: SystemCoreConfig,
        private val sharedPreferences: SharedPreferences,
        private val savesManager: SavesManager,
        private val statesManager: StatesManager,
        private val statesPreviewManager: StatesPreviewManager,
        private val coreVariablesManager: CoreVariablesManager,
        private val rumbleManager: RumbleManager,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BaseGameScreenViewModel(
                appContext,
                game,
                settingsManager,
                inputDeviceManager,
                controllerConfigsManager,
                system,
                systemCoreConfig,
                sharedPreferences,
                savesManager,
                statesManager,
                statesPreviewManager,
                coreVariablesManager,
                rumbleManager,
            ) as T
        }
    }

    private val sideEffects = GameViewModelSideEffects(viewModelScope)
    val retroGameView =
        GameViewModelRetroGameView(
            appContext,
            system,
            systemCoreConfig,
            settingsManager,
            coreVariablesManager,
            sideEffects,
            rumbleManager,
            viewModelScope,
        )
    private val tilt = GameViewModelTilt(appContext, settingsManager)
    private val inputs =
        GameViewModelInput(
            appContext,
            system,
            systemCoreConfig,
            inputDeviceManager,
            controllerConfigsManager,
            retroGameView,
            tilt,
            sideEffects,
            viewModelScope,
        )
    private val touchControls =
        GameViewModelTouchControls(
            settingsManager,
            TouchControllerSettingsManager(sharedPreferences),
            retroGameView,
            inputs,
            tilt,
            sideEffects,
            viewModelScope,
        )
    private val saves =
        GameViewModelSaves(
            appContext,
            system,
            game,
            systemCoreConfig,
            retroGameView,
            settingsManager,
            savesManager,
            statesManager,
            statesPreviewManager,
            sideEffects,
        )

    val loadingState = MutableStateFlow(false)

    fun disableSavePersistence() {
        saves.persistSaves = false
    }

    private inline fun withLoading(block: () -> Unit) {
        loadingState.value = true
        block()
        loadingState.value = false
    }

    fun getGameState(): Flow<GameViewModelRetroGameView.GameState> {
        return retroGameView.getGameState()
    }

    fun getSideEffects(): Flow<GameViewModelSideEffects.UiEffect> {
        return sideEffects.getUiEffects()
    }

    fun getTiltConfiguration(): Flow<TiltConfiguration> {
        return tilt.getTiltConfiguration()
    }

    fun getSimulatedTiltEvents(): Flow<InputState> {
        return tilt.getSimulatedTiltEvents()
    }

    fun getTouchControlsSettings(
        density: Density,
        insets: WindowInsets,
    ): Flow<TouchControllerSettingsManager.Settings?> {
        return touchControls.getTouchControlsSettings(density, insets)
    }

    fun getTouchHapticFeedbackMode(): Flow<HapticFeedbackMode> {
        return touchControls.getTouchHapticFeedbackMode()
    }

    fun createRetroView(
        context: Context,
        lifecycle: LifecycleOwner,
    ): GLRetroView {
        val (gameData, result) = retroGameView.createRetroView(context, lifecycle)
        viewModelScope.launch {
            gameData.quickSaveData?.let {
                saves.restoreAutoSaveAsync(it)
            }
        }
        return result
    }

    suspend fun loadGame(
        applicationContext: Context,
        game: Game,
        systemCoreConfig: SystemCoreConfig,
        gameLoader: GameLoader,
        requestLoadSave: Boolean,
    ) {
        Timber.i("Calling load game: $game")
        retroGameView.initialize(applicationContext, game, systemCoreConfig, gameLoader, requestLoadSave)
    }

    fun showEditControls(show: Boolean) {
        touchControls.showEditControls(show)
    }

    fun isEditControlShown(): Flow<Boolean> {
        return touchControls.isEditControlsShown()
    }

    fun updateTouchControllerSettings(touchControllerSettings: TouchControllerSettingsManager.Settings) {
        touchControls.updateTouchControllerSettings(touchControllerSettings)
    }

    fun resetTouchControls() {
        touchControls.resetTouchControls()
    }

    fun onScreenOrientationChanged(orientation: TouchControllerSettingsManager.Orientation) {
        touchControls.updateScreenOrientation(orientation)
    }

    fun isTouchControllerVisible(): Flow<Boolean> {
        return touchControls.isTouchControllerVisible()
    }

    fun getTouchControllerConfig(): Flow<ControllerConfig> {
        return touchControls.getTouchControllerConfig()
    }

    fun changeTiltConfiguration(tiltConfig: TiltConfiguration) {
        tilt.changeTiltConfiguration(tiltConfig)
    }

    fun isMenuPressed(): Flow<Boolean> {
        return touchControls.isMenuPressed()
    }

    suspend fun saveSlot(index: Int) {
        if (loadingState.value) return
        withLoading {
            saves.saveSlot(index)
        }
    }

    suspend fun loadSlot(index: Int) {
        if (loadingState.value) return
        withLoading {
            saves.loadSlot(index)
        }
    }

    fun saveQuickSave() {
        Timber.d("Saving quick save")
        if (loadingState.value) return
        withLoading {
            saves.saveQuickSave()
        }
    }

    fun loadQuickSave() {
        Timber.d("Loading quick save")
        if (loadingState.value) return
        withLoading {
            saves.loadQuickSave()
        }
    }

    // The fast forward speed the player picked last (2x unless they chose 4x).
    private var fastForwardSpeed = GameMenuContract.FAST_FORWARD_SPEEDS.first()

    /** The controller shortcut: fast forward at the speed picked last, or back to normal. */
    fun toggleFastForward() {
        retroGameView.retroGameView?.apply {
            frameSpeed = if (frameSpeed == 1) fastForwardSpeed else 1
        }
    }

    /** 1 is normal speed; otherwise one of [GameMenuContract.FAST_FORWARD_SPEEDS]. */
    fun setFrameSpeed(speed: Int) {
        val allowed = speed.takeIf { it in GameMenuContract.FAST_FORWARD_SPEEDS } ?: 1
        if (allowed > 1) fastForwardSpeed = allowed
        retroGameView.retroGameView?.frameSpeed = allowed
    }

    suspend fun reset() =
        withLoading {
            try {
                delay(appContext.longAnimationDuration().toLong())
                retroGameView.retroGameViewFlow().reset()
            } catch (e: Throwable) {
                Timber.e(e, "Error in reset")
            }
        }

    /** Runs before the game closes (for example, coming home from a visit to the host's world). */
    var beforeFinish: (suspend () -> Unit)? = null

    fun requestFinish() {
        if (loadingState.value) return
        viewModelScope.launch {
            withLoading {
                runCatching { beforeFinish?.invoke() }.onFailure { Timber.e(it, "Before finish failed") }
                val snapshot = saves.captureSaveSnapshot(true) ?: return@launch
                saves.writeSaveSnapshot(snapshot)
                sideEffects.requestSuccessfulFinish()
            }
        }
    }

    /**
     * A quiet save while playing (auto-resume snapshot and in-game save), so a crash or a
     * closed app loses at most a few seconds of play.
     */
    fun requestPeriodicSave() {
        if (loadingState.value) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val snapshot = saves.captureSaveSnapshot(true)
                saves.writeSaveSnapshot(snapshot)
            }.onFailure { Timber.w(it, "Periodic save failed") }
        }
    }

    /** Writes the in-game save and the auto-resume snapshot to disk, and waits for it. */
    suspend fun saveNow() {
        withContext(Dispatchers.IO) {
            runCatching { saves.writeSaveSnapshot(saves.captureSaveSnapshot(true)) }.onFailure { Timber.w(it, "Save failed") }
        }
    }

    fun requestBackgroundSave() {
        if (loadingState.value) return
        GameService.schedule {
            val snapshot = saves.captureSaveSnapshot(false)
            saves.writeSaveSnapshot(snapshot)
        }
    }

    /** Each press of the game's A button (touch or controller), for talking to other players. */
    val aButtonPresses = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** The game's buttons, as a box the app shows over the game (like the talk box) uses them. */
    enum class MenuKey { UP, DOWN, LEFT, RIGHT, A, B }

    /**
     * While true, the control pad and the A and B buttons drive the app's box instead of the
     * game: presses come out of [menuKeys]. Releases (and a centred pad) still reach the game,
     * so nothing it saw pressed stays held.
     */
    @Volatile var capturingInput = false
    val menuKeys = MutableSharedFlow<MenuKey>(extraBufferCapacity = 8)

    fun handleVirtualInputEvent(events: List<InputEvent>) {
        if (capturingInput) {
            touchControls.handleVirtualInputEvent(events.mapNotNull(::captureVirtual))
            return
        }
        menuSteps.reset()
        if (events.any { it is InputEvent.Button && it.id == KeyEvent.KEYCODE_BUTTON_A && it.pressed }) {
            aButtonPresses.tryEmit(Unit)
        }
        touchControls.handleVirtualInputEvent(events)
    }

    private fun captureVirtual(event: InputEvent): InputEvent? =
        when (event) {
            is InputEvent.Button ->
                when {
                    // The menu button still opens the game menu.
                    event.id == KeyEvent.KEYCODE_BUTTON_MODE || !event.pressed -> event
                    else -> {
                        captureKey(event.id)
                        null
                    }
                }
            is InputEvent.DiscreteDirection -> {
                captureDirection(event.direction.x, event.direction.y)
                event.copy(direction = Offset.Zero)
            }
            is InputEvent.ContinuousDirection -> {
                captureDirection(event.direction.x, event.direction.y)
                event.copy(direction = Offset.Zero)
            }
            else -> event
        }

    /** A controller key while [capturingInput]: true when the box used it (the game must not get it). */
    fun captureKey(keyCode: Int): Boolean {
        val key =
            when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> MenuKey.A
                KeyEvent.KEYCODE_BUTTON_B -> MenuKey.B
                KeyEvent.KEYCODE_DPAD_UP -> MenuKey.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> MenuKey.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> MenuKey.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> MenuKey.RIGHT
                else -> return false
            }
        menuKeys.tryEmit(key)
        return true
    }

    /** A controller's hat or stick while [capturingInput] (y grows downwards). */
    fun captureControllerDirection(
        x: Float,
        y: Float,
    ) = captureDirection(x, -y)

    private val menuSteps = MenuSteps()

    private fun captureDirection(
        x: Float,
        y: Float,
    ) {
        menuSteps.step(x, y, android.os.SystemClock.uptimeMillis())?.let { menuKeys.tryEmit(it) }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)

        owner.lifecycle.addObserver(tilt)
        owner.lifecycle.addObserver(inputs)
        owner.lifecycle.addObserver(retroGameView)
        owner.lifecycle.addObserver(touchControls)
    }

    fun sendKeyEvent(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        return inputs.sendKeyEvent(keyCode, event)
    }

    fun sendMotionEvent(event: MotionEvent): Boolean {
        return inputs.sendMotionEvent(event)
    }
}

/**
 * The control pad as steps for the app's boxes, one per press like the game's own menus (pad
 * coordinates: y grows upwards). The pad has to come back near the centre before the next
 * step, so a finger that wobbles on the pad doesn't skip choices, and steps are never closer
 * than [STEP_MS].
 */
class MenuSteps {
    private var held = false
    private var lastStep = Long.MIN_VALUE / 2

    fun reset() {
        held = false
    }

    /** The step for the pad at ([x], [y]) at time [now] (ms), or null. */
    fun step(
        x: Float,
        y: Float,
        now: Long,
    ): BaseGameScreenViewModel.MenuKey? {
        val ax = kotlin.math.abs(x)
        val ay = kotlin.math.abs(y)
        if (ax < RELEASE_ZONE && ay < RELEASE_ZONE) {
            held = false
            return null
        }
        if (held || (ax < PRESS_ZONE && ay < PRESS_ZONE)) return null
        held = true
        if (now - lastStep < STEP_MS) return null
        lastStep = now
        return when {
            ax >= ay && x > 0 -> BaseGameScreenViewModel.MenuKey.RIGHT
            ax >= ay -> BaseGameScreenViewModel.MenuKey.LEFT
            y > 0 -> BaseGameScreenViewModel.MenuKey.UP
            else -> BaseGameScreenViewModel.MenuKey.DOWN
        }
    }

    companion object {
        // How far the pad must move to count, and how close to the centre it must come back.
        private const val PRESS_ZONE = 0.5f
        private const val RELEASE_ZONE = 0.3f
        const val STEP_MS = 150L
    }
}
