package com.swordfish.lemuroid.app.mobile.feature.game

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.PadKitScope
import gg.padkit.controls.ControlButton
import gg.padkit.controls.ControlCross
import gg.padkit.ids.Id
import gg.padkit.ui.DefaultCrossForeground

/**
 * Handheld-style touch controls (Game Boy, Game Boy Color, Game Boy Advance): outlined keys
 * that light up when pressed.
 *
 * Portrait: the game fills the top of the screen and the controls sit below it.
 * Landscape: the game is centered and the controls sit at the sides and corners.
 * The layout follows the phone as it rotates.
 */
object ClassicGamePad {
    val outline = Color.White.copy(alpha = 0.72f)
    val pressedFill = Color.White.copy(alpha = 0.30f)
    val idleFill = Color.Black.copy(alpha = 0.12f)
    const val STROKE_DP = 2
}

/** How big the game picture is drawn. */
enum class ClassicScreenSize {
    // As large as the space allows, keeping the game's shape.
    FIT,

    // The largest whole-number zoom of the console's resolution, so every pixel is the same size.
    SHARP_PIXELS,
}

/** Where the game sits in portrait, in the space above the controls. */
enum class ClassicScreenPosition { TOP, MIDDLE }

/** Places the game picture and, if [showControls], the controls. Reports where the game should draw. */
@Composable
fun PadKitScope.ClassicGameLayout(
    isLandscape: Boolean,
    gameAspectRatio: Float,
    nativeWidth: Int,
    nativeHeight: Int,
    hasShoulderButtons: Boolean,
    showControls: Boolean,
    settings: TouchControllerSettingsManager.Settings?,
    screenSize: ClassicScreenSize = ClassicScreenSize.FIT,
    screenPosition: ClassicScreenPosition = ClassicScreenPosition.TOP,
    onGameAreaPositioned: (Rect) -> Unit,
) {
    val scale =
        settings?.let {
            TouchControllerSettingsManager.MIN_SCALE +
                (TouchControllerSettingsManager.MAX_SCALE - TouchControllerSettingsManager.MIN_SCALE) * it.scale
        } ?: 1f
    val marginX = (settings?.marginX ?: 0f) * TouchControllerSettingsManager.MAX_MARGINS
    val marginY = (settings?.marginY ?: 0f) * TouchControllerSettingsManager.MAX_MARGINS
    val density = LocalDensity.current

    // Size of the game picture inside the given space.
    fun gameSize(
        availableWidth: Dp,
        availableHeight: Dp,
    ): DpSize {
        if (availableWidth <= 0.dp || availableHeight <= 0.dp) return DpSize(0.dp, 0.dp)
        val fitWidth = min(availableWidth, availableHeight * gameAspectRatio)
        if (screenSize == ClassicScreenSize.FIT) return DpSize(fitWidth, fitWidth / gameAspectRatio)
        with(density) {
            val zoom = minOf(availableWidth.toPx() / nativeWidth, availableHeight.toPx() / nativeHeight).toInt()
            if (zoom < 1) return DpSize(fitWidth, fitWidth / gameAspectRatio)
            return DpSize((nativeWidth * zoom).toDp(), (nativeHeight * zoom).toDp())
        }
    }

    if (!isLandscape) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            // The game takes the space the controls leave free.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                val size = gameSize(maxWidth, maxHeight)
                Box(
                    modifier =
                        Modifier
                            .size(size)
                            .align(if (screenPosition == ClassicScreenPosition.MIDDLE) Alignment.Center else Alignment.TopCenter)
                            .onGloballyPositioned { onGameAreaPositioned(it.boundsInRoot()) },
                )
            }
            if (showControls) {
                PortraitControls(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(start = 16.dp + marginX.dp, end = 16.dp + marginX.dp, top = 12.dp, bottom = 16.dp + marginY.dp),
                    scale = scale,
                    hasShoulderButtons = hasShoulderButtons,
                )
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val size = gameSize(maxWidth, maxHeight)
            Box(
                modifier =
                    Modifier
                        .size(size)
                        .align(Alignment.Center)
                        .onGloballyPositioned { onGameAreaPositioned(it.boundsInRoot()) },
            )
            if (showControls) {
                LandscapeControls(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(start = 12.dp + marginX.dp, end = 12.dp + marginX.dp, top = 8.dp, bottom = 12.dp + marginY.dp),
                    scale = scale,
                    hasShoulderButtons = hasShoulderButtons,
                )
            }
        }
    }
}

@Composable
private fun PadKitScope.PortraitControls(
    modifier: Modifier,
    scale: Float,
    hasShoulderButtons: Boolean,
) {
    // Three rows, low on the screen where thumbs reach:
    // shoulder keys and menu, then Select and Start centered, then the D-pad and A/B.
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.fillMaxWidth()) {
            if (hasShoulderButtons) {
                ShoulderButton(Modifier.align(Alignment.CenterStart), KeyEvent.KEYCODE_BUTTON_L1, "L", isLeft = true, scale = scale)
                ShoulderButton(Modifier.align(Alignment.CenterEnd), KeyEvent.KEYCODE_BUTTON_R1, "R", isLeft = false, scale = scale)
            }
            MenuButton(Modifier.align(Alignment.Center), scale)
        }
        Spacer(Modifier.height(16.dp * scale))
        StartSelect(scale)
        Spacer(Modifier.height(8.dp * scale))
        Box(modifier = Modifier.fillMaxWidth()) {
            DPad(Modifier.align(Alignment.CenterStart), scale)
            FaceButtons(Modifier.align(Alignment.CenterEnd), scale)
        }
    }
}

@Composable
private fun PadKitScope.LandscapeControls(
    modifier: Modifier,
    scale: Float,
    hasShoulderButtons: Boolean,
) {
    Box(modifier = modifier) {
        if (hasShoulderButtons) {
            ShoulderButton(Modifier.align(Alignment.TopStart), KeyEvent.KEYCODE_BUTTON_L1, "L", isLeft = true, scale = scale)
            ShoulderButton(Modifier.align(Alignment.TopEnd), KeyEvent.KEYCODE_BUTTON_R1, "R", isLeft = false, scale = scale)
        }
        MenuButton(Modifier.align(Alignment.TopCenter), scale)
        DPad(Modifier.align(Alignment.BottomStart), scale)
        FaceButtons(Modifier.align(Alignment.BottomEnd), scale)
        StartSelect(scale, Modifier.align(Alignment.BottomEnd).offset(x = (-180).dp * scale, y = 0.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Individual keys

@Composable
private fun PadKitScope.DPad(
    modifier: Modifier,
    scale: Float,
) {
    val size = 150.dp * scale
    ControlCross(
        modifier = modifier.size(size),
        id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD),
        allowDiagonals = true,
        background = { },
        foreground = { direction ->
            DefaultCrossForeground(
                modifier = Modifier.fillMaxSize(),
                directionState = direction,
                allowDiagonals = true,
                leftDial = { ArrowKey(it, Icons.Default.KeyboardArrowLeft) },
                rightDial = { ArrowKey(it, Icons.Default.KeyboardArrowRight) },
                topDial = { ArrowKey(it, Icons.Default.KeyboardArrowUp) },
                bottomDial = { ArrowKey(it, Icons.Default.KeyboardArrowDown) },
                foregroundComposite = { },
            )
        },
    )
}

@Composable
private fun ArrowKey(
    pressed: State<Boolean>,
    icon: ImageVector,
) {
    OutlinedKey(
        pressed = pressed.value,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxSize().padding(5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ClassicGamePad.outline, modifier = Modifier.fillMaxSize(0.75f))
    }
}

@Composable
private fun PadKitScope.FaceButtons(
    scale: Float,
) = FaceButtons(Modifier, scale)

@Composable
private fun PadKitScope.FaceButtons(
    modifier: Modifier,
    scale: Float,
) {
    // B sits lower-left of A, like on the console.
    val button = 66.dp * scale
    Box(modifier = modifier.size(width = button * 2.25f, height = button * 1.65f)) {
        RoundKey(Modifier.align(Alignment.BottomStart), KeyEvent.KEYCODE_BUTTON_B, "B", button)
        RoundKey(Modifier.align(Alignment.TopEnd), KeyEvent.KEYCODE_BUTTON_A, "A", button)
    }
}

@Composable
private fun PadKitScope.RoundKey(
    modifier: Modifier,
    keyCode: Int,
    label: String,
    size: Dp,
) {
    ControlButton(
        modifier = modifier.size(size),
        id = Id.Key(keyCode),
        foreground = { pressed ->
            OutlinedKey(pressed = pressed.value, shape = CircleShape, modifier = Modifier.fillMaxSize()) {
                KeyLabel(label, (size.value * 0.36f).sp)
            }
        },
        background = { },
    )
}

@Composable
private fun PadKitScope.ShoulderButton(
    modifier: Modifier,
    keyCode: Int,
    label: String,
    isLeft: Boolean,
    scale: Float,
) {
    // A tab with a rounder outer top corner, like the shoulder keys on a handheld.
    val shape =
        if (isLeft) {
            RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomEnd = 22.dp, bottomStart = 8.dp)
        } else {
            RoundedCornerShape(topStart = 8.dp, topEnd = 22.dp, bottomEnd = 8.dp, bottomStart = 22.dp)
        }
    ControlButton(
        modifier = modifier.size(width = 104.dp * scale, height = 48.dp * scale),
        id = Id.Key(keyCode),
        foreground = { pressed ->
            OutlinedKey(pressed = pressed.value, shape = shape, modifier = Modifier.fillMaxSize()) {
                KeyLabel(label, (20 * scale).sp)
            }
        },
        background = { },
    )
}

@Composable
private fun PadKitScope.StartSelect(
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp * scale)) {
        PillKey(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", scale)
        PillKey(KeyEvent.KEYCODE_BUTTON_START, "START", scale)
    }
}

@Composable
private fun PadKitScope.PillKey(
    keyCode: Int,
    label: String,
    scale: Float,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        ControlButton(
            // A taller touch area than the drawing, so the small key is easy to hit.
            modifier = Modifier.size(width = 60.dp * scale, height = 34.dp * scale),
            id = Id.Key(keyCode),
            foreground = { pressed ->
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    OutlinedKey(
                        pressed = pressed.value,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.size(width = 56.dp * scale, height = 22.dp * scale),
                    ) { }
                }
            },
            background = { },
        )
        Text(
            text = label,
            color = ClassicGamePad.outline,
            fontSize = (9 * scale).sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun PadKitScope.MenuButton(
    modifier: Modifier,
    scale: Float,
) {
    ControlButton(
        modifier = modifier.size(46.dp * scale),
        id = Id.Key(KeyEvent.KEYCODE_BUTTON_MODE),
        foreground = { pressed ->
            OutlinedKey(pressed = pressed.value, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Default.Menu, contentDescription = null, tint = ClassicGamePad.outline, modifier = Modifier.fillMaxSize(0.65f))
            }
        },
        background = { },
    )
}

@Composable
private fun OutlinedKey(
    pressed: Boolean,
    shape: Shape,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            modifier
                .background(if (pressed) ClassicGamePad.pressedFill else ClassicGamePad.idleFill, shape)
                .border(ClassicGamePad.STROKE_DP.dp, ClassicGamePad.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun KeyLabel(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
) {
    Text(text = text, color = ClassicGamePad.outline, fontSize = size, fontWeight = FontWeight.Medium)
}
