package com.swordfish.lemuroid.app.mobile.feature.game

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.swordfish.touchinput.radial.layouts.shared.ComposeTouchLayouts
import com.swordfish.touchinput.radial.settings.TouchControllerSettingsManager
import gg.padkit.PadKitScope
import gg.padkit.controls.ControlButton
import gg.padkit.controls.ControlCross
import gg.padkit.ids.Id

/**
 * Handheld-style touch controls (Game Boy, Game Boy Color, Game Boy Advance): thin outlined keys
 * that light up when pressed. Every key reacts a little outside its outline, so near misses count.
 *
 * Portrait: the game sits above the controls, which stay low where thumbs rest.
 * Landscape: the game is centered, the D-pad bottom left, A and B bottom right.
 * The layout follows the phone as it rotates.
 */
object ClassicGamePad {
    val outline = Color.White.copy(alpha = 0.58f)
    val pressedFill = Color.White.copy(alpha = 0.28f)
    val menuFill = Color.Black.copy(alpha = 0.25f)
    val menuBars = Color.White.copy(alpha = 0.72f)
    val stroke = 1.6.dp
}

/** How big the game picture is drawn. */
enum class ClassicScreenSize {
    // As large as the space allows, keeping the game's shape.
    FIT,

    // The largest whole-number zoom of the console's resolution, so every pixel is the same size.
    SHARP_PIXELS,
}

/** Where the game sits in portrait, in the space above the controls. */
enum class ClassicScreenPosition {
    // Right under the status bar.
    TOP,

    // Centered for the eye: a little above the middle of the free space, away from the camera.
    MIDDLE,
}

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
    screenPosition: ClassicScreenPosition = ClassicScreenPosition.MIDDLE,
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
                val freeSpace = (maxHeight - size.height).coerceAtLeast(0.dp)
                val top = if (screenPosition == ClassicScreenPosition.MIDDLE) freeSpace * EYE_LINE else 0.dp
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = top)
                            .size(size)
                            .onGloballyPositioned { onGameAreaPositioned(it.boundsInRoot()) },
                )
            }
            if (showControls) {
                PortraitControls(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(start = 12.dp + marginX.dp, end = 12.dp + marginX.dp, top = 8.dp, bottom = 8.dp + marginY.dp),
                    scale = scale,
                    hasShoulderButtons = hasShoulderButtons,
                )
            }
        }
    } else {
        // Game and controls stay clear of the camera cutout.
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val size = gameSize(maxWidth, maxHeight)
                Box(
                    modifier =
                        Modifier
                            .size(size)
                            .align(Alignment.Center)
                            .onGloballyPositioned { onGameAreaPositioned(it.boundsInRoot()) },
                )
            }
            if (showControls) {
                LandscapeControls(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(start = 12.dp + marginX.dp, end = 12.dp + marginX.dp, top = 7.dp, bottom = 13.dp + marginY.dp),
                    scale = scale,
                    hasShoulderButtons = hasShoulderButtons,
                )
            }
        }
    }
}

// Share of the free space left above the game in portrait (the rest goes below it).
private const val EYE_LINE = 0.4f

// Touch areas. The drawn keys sit inside them, a little smaller.
private val ROUND_KEY = 58.dp
private val PILL_KEY = DpSize(56.dp, 34.dp)
private val SHOULDER_KEY = DpSize(84.dp, 46.dp)
private val MENU_KEY = 44.dp
private val CROSS = 150.dp

@Composable
private fun PadKitScope.PortraitControls(
    modifier: Modifier,
    scale: Float,
    hasShoulderButtons: Boolean,
) {
    // L, menu and R on top. Below: the D-pad on the left; Select and Start right above B and A.
    Box(modifier = modifier.height(253.dp * scale)) {
        MenuButton(Modifier.align(Alignment.TopCenter), scale)
        if (hasShoulderButtons) {
            ShoulderButton(Modifier.align(Alignment.TopStart).offset((-3).dp * scale, 4.5.dp * scale), KeyEvent.KEYCODE_BUTTON_L1, "L", true, scale)
            ShoulderButton(Modifier.align(Alignment.TopEnd).offset(3.dp * scale, 4.5.dp * scale), KeyEvent.KEYCODE_BUTTON_R1, "R", false, scale)
        }
        PillKey(Modifier.align(Alignment.TopEnd).offset((-84).dp * scale, 111.dp * scale), KeyEvent.KEYCODE_BUTTON_SELECT, scale)
        PillKey(Modifier.align(Alignment.TopEnd).offset((-2).dp * scale, 111.dp * scale), KeyEvent.KEYCODE_BUTTON_START, scale)
        DPad(Modifier.align(Alignment.BottomStart).offset(x = (-3).dp * scale), scale)
        RoundKey(Modifier.align(Alignment.BottomEnd).offset((-83).dp * scale, (-2).dp * scale), KeyEvent.KEYCODE_BUTTON_B, "B", scale)
        RoundKey(Modifier.align(Alignment.BottomEnd).offset((-1).dp * scale, (-2).dp * scale), KeyEvent.KEYCODE_BUTTON_A, "A", scale)
    }
}

@Composable
private fun PadKitScope.LandscapeControls(
    modifier: Modifier,
    scale: Float,
    hasShoulderButtons: Boolean,
) {
    // L and R in the top corners, the menu at the top middle, Select and Start low beside B.
    Box(modifier = modifier) {
        if (hasShoulderButtons) {
            ShoulderButton(Modifier.align(Alignment.TopStart).offset((-3).dp * scale, (-3.5).dp * scale), KeyEvent.KEYCODE_BUTTON_L1, "L", true, scale)
            ShoulderButton(Modifier.align(Alignment.TopEnd).offset(3.dp * scale, (-3.5).dp * scale), KeyEvent.KEYCODE_BUTTON_R1, "R", false, scale)
        }
        MenuButton(Modifier.align(Alignment.TopCenter).offset(y = (-9).dp * scale), scale)
        DPad(Modifier.align(Alignment.BottomStart).offset((-3).dp * scale, 2.dp * scale), scale)
        PillKey(Modifier.align(Alignment.BottomEnd).offset((-281).dp * scale, 2.dp * scale), KeyEvent.KEYCODE_BUTTON_SELECT, scale)
        PillKey(Modifier.align(Alignment.BottomEnd).offset((-225).dp * scale, 2.dp * scale), KeyEvent.KEYCODE_BUTTON_START, scale)
        RoundKey(Modifier.align(Alignment.BottomEnd).offset((-83).dp * scale, 1.dp * scale), KeyEvent.KEYCODE_BUTTON_B, "B", scale)
        RoundKey(Modifier.align(Alignment.BottomEnd).offset((-1).dp * scale, 1.dp * scale), KeyEvent.KEYCODE_BUTTON_A, "A", scale)
    }
}

// ---------------------------------------------------------------------------------------------
// Individual keys

@Composable
private fun PadKitScope.DPad(
    modifier: Modifier,
    scale: Float,
) {
    // Four separate keys drawn, one touch area: touching between two arrows presses both (diagonal).
    ControlCross(
        modifier = modifier.size(CROSS * scale),
        id = Id.DiscreteDirection(ComposeTouchLayouts.MOTION_SOURCE_DPAD),
        allowDiagonals = true,
        background = { },
        foreground = { direction -> CrossKeys(direction) },
    )
}

@Composable
private fun CrossKeys(direction: State<Offset>) {
    // The pad library reports up as a positive y.
    val up = remember(direction) { derivedStateOf { direction.value.y > 0.5f } }
    val down = remember(direction) { derivedStateOf { direction.value.y < -0.5f } }
    val left = remember(direction) { derivedStateOf { direction.value.x < -0.5f } }
    val right = remember(direction) { derivedStateOf { direction.value.x > 0.5f } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Three keys across with small gaps: 46 + 6 + 46 + 6 + 46 at full size.
        val key = maxWidth * (46f / 150f)
        val step = maxWidth * (52f / 150f)
        ArrowKey(Modifier.offset(step, 0.dp).size(key), up, 0f)
        ArrowKey(Modifier.offset(0.dp, step).size(key), left, 270f)
        ArrowKey(Modifier.offset(step * 2, step).size(key), right, 90f)
        ArrowKey(Modifier.offset(step, step * 2).size(key), down, 180f)
    }
}

@Composable
private fun ArrowKey(
    modifier: Modifier,
    pressed: State<Boolean>,
    rotation: Float,
) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        modifier =
            modifier
                .background(if (pressed.value) ClassicGamePad.pressedFill else Color.Transparent, shape)
                .border(ClassicGamePad.stroke, ClassicGamePad.outline, shape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // An outlined triangle pointing up, turned to face its direction.
            val t = size.minDimension * 0.29f
            val c = center
            val triangle =
                Path().apply {
                    moveTo(c.x, c.y - t * 0.95f)
                    lineTo(c.x + t * 1.05f, c.y + t * 0.8f)
                    lineTo(c.x - t * 1.05f, c.y + t * 0.8f)
                    close()
                }
            rotate(rotation, c) {
                drawPath(triangle, ClassicGamePad.outline, style = Stroke(width = ClassicGamePad.stroke.toPx(), join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
private fun PadKitScope.RoundKey(
    modifier: Modifier,
    keyCode: Int,
    label: String,
    scale: Float,
) {
    ControlButton(
        modifier = modifier.size(ROUND_KEY * scale),
        id = Id.Key(keyCode),
        foreground = { pressed ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(4.dp * scale)
                        .background(if (pressed.value) ClassicGamePad.pressedFill else Color.Transparent, CircleShape)
                        .border(ClassicGamePad.stroke, ClassicGamePad.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, color = ClassicGamePad.outline, fontSize = (24 * scale).sp, fontWeight = FontWeight.Normal)
            }
        },
        background = { },
    )
}

/** A shoulder tab: flat top, and a bottom edge that curves up toward the middle of the phone. */
private class ShoulderShape(private val isLeft: Boolean) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { 9.dp.toPx() }

        // Drawn for the left key; the right key is its mirror image.
        fun x(value: Float) = if (isLeft) value else w - value
        val path =
            Path().apply {
                moveTo(x(r), 0f)
                lineTo(x(w - r), 0f)
                quadraticBezierTo(x(w), 0f, x(w), r)
                lineTo(x(w), h * 0.42f)
                cubicTo(x(w), h * 0.66f, x(w * 0.5f), h * 0.74f, x(r * 1.65f), h)
                quadraticBezierTo(x(0f), h, x(0f), h - r * 1.3f)
                lineTo(x(0f), r)
                quadraticBezierTo(x(0f), 0f, x(r), 0f)
                close()
            }
        return Outline.Generic(path)
    }
}

@Composable
private fun PadKitScope.ShoulderButton(
    modifier: Modifier,
    keyCode: Int,
    label: String,
    isLeft: Boolean,
    scale: Float,
) {
    val shape = remember(isLeft) { ShoulderShape(isLeft) }
    ControlButton(
        modifier = modifier.size(SHOULDER_KEY * scale),
        id = Id.Key(keyCode),
        foreground = { pressed ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 3.dp * scale, vertical = 3.5.dp * scale)
                        .background(if (pressed.value) ClassicGamePad.pressedFill else Color.Transparent, shape)
                        .border(ClassicGamePad.stroke, ClassicGamePad.outline, shape),
            ) {
                Text(
                    text = label,
                    color = ClassicGamePad.outline,
                    fontSize = (20 * scale).sp,
                    fontWeight = FontWeight.Normal,
                    modifier =
                        Modifier
                            .align(if (isLeft) Alignment.CenterStart else Alignment.CenterEnd)
                            .padding(
                                start = if (isLeft) 9.dp * scale else 0.dp,
                                end = if (isLeft) 0.dp else 9.dp * scale,
                                bottom = 4.dp * scale,
                            ),
                )
            }
        },
        background = { },
    )
}

@Composable
private fun PadKitScope.PillKey(
    modifier: Modifier,
    keyCode: Int,
    scale: Float,
) {
    // Select and Start: small pills, with a touch area much bigger than the drawing.
    ControlButton(
        modifier = modifier.size(PILL_KEY * scale),
        id = Id.Key(keyCode),
        foreground = { pressed ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                val shape = RoundedCornerShape(50)
                Box(
                    modifier =
                        Modifier
                            .size(width = 40.dp * scale, height = 17.dp * scale)
                            .background(if (pressed.value) ClassicGamePad.pressedFill else Color.Transparent, shape)
                            .border(ClassicGamePad.stroke, ClassicGamePad.outline, shape),
                )
            }
        },
        background = { },
    )
}

@Composable
private fun PadKitScope.MenuButton(
    modifier: Modifier,
    scale: Float,
) {
    ControlButton(
        modifier = modifier.size(MENU_KEY * scale),
        id = Id.Key(KeyEvent.KEYCODE_BUTTON_MODE),
        foreground = { pressed ->
            val shape = RoundedCornerShape(6.dp * scale)
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(4.dp * scale)
                        .background(if (pressed.value) ClassicGamePad.pressedFill else ClassicGamePad.menuFill, shape)
                        .border(ClassicGamePad.stroke, ClassicGamePad.outline, shape),
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val barWidth = size.width * 0.6f
                    val barHeight = size.height * 0.11f
                    val left = (size.width - barWidth) / 2
                    for (top in listOf(0.27f, 0.45f, 0.63f)) {
                        drawRoundRect(
                            color = ClassicGamePad.menuBars,
                            topLeft = Offset(left, size.height * top),
                            size = Size(barWidth, barHeight),
                            cornerRadius = CornerRadius(1.2.dp.toPx()),
                        )
                    }
                }
            }
        },
        background = { },
    )
}
