package com.swordfish.lemuroid.app.mobile.feature.game

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import android.graphics.Canvas
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import gg.padkit.PadKit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the handheld touch controls in portrait and landscape and saves PNGs to docs/previews,
 * so the layout can be checked without a phone. The grey box stands in for the game picture.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ClassicGamePadPreviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = "w393dp-h852dp-port-xhdpi")
    fun gbaPortrait() = render("gba-portrait.png", isLandscape = false, aspect = 3f / 2f, shoulders = true)

    @Test
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi")
    fun gbaLandscape() = render("gba-landscape.png", isLandscape = true, aspect = 3f / 2f, shoulders = true)

    @Test
    @Config(qualifiers = "w393dp-h852dp-port-xhdpi")
    fun gbPortrait() = render("gb-portrait.png", isLandscape = false, aspect = 10f / 9f, shoulders = false)

    @Test
    @Config(qualifiers = "w393dp-h852dp-port-xhdpi")
    fun gbaPortraitSharpMiddle() =
        render(
            "gba-portrait-sharp-middle.png",
            isLandscape = false,
            aspect = 3f / 2f,
            shoulders = true,
            size = ClassicScreenSize.SHARP_PIXELS,
            position = ClassicScreenPosition.MIDDLE,
        )

    private fun render(
        name: String,
        isLandscape: Boolean,
        aspect: Float,
        shoulders: Boolean,
        size: ClassicScreenSize = ClassicScreenSize.FIT,
        position: ClassicScreenPosition = ClassicScreenPosition.TOP,
    ) {
        // The pad library keeps a loop running, so step the clock by hand instead of waiting for idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val gameArea = remember { mutableStateOf<Rect?>(null) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                gameArea.value?.let { area ->
                    with(density) {
                        Box(
                            modifier =
                                Modifier
                                    .offset(area.left.toDp(), area.top.toDp())
                                    .size(area.width.toDp(), area.height.toDp())
                                    .background(Color(0xFF3A3F4B)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Game picture", color = Color.White)
                        }
                    }
                }
                PadKit(modifier = Modifier.fillMaxSize(), onInputEvents = { }) {
                    ClassicGameLayout(
                        isLandscape = isLandscape,
                        gameAspectRatio = aspect,
                        nativeWidth = if (shoulders) 240 else 160,
                        nativeHeight = if (shoulders) 160 else 144,
                        screenSize = size,
                        screenPosition = position,
                        hasShoulderButtons = shoulders,
                        showControls = true,
                        settings = null,
                        onGameAreaPositioned = { gameArea.value = it },
                    )
                }
            }
        }
        repeat(10) { compose.mainClock.advanceTimeByFrame() }
        // Draw the window straight into a bitmap (the test screenshot helper waits for a redraw forever).
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val folder = File(System.getProperty("previews.output") ?: "build/previews").apply { mkdirs() }
        File(folder, name).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
