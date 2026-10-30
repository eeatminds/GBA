package com.swordfish.lemuroid.app.mobile.feature.game

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.FloatingMenu
import com.swordfish.lemuroid.app.mobile.feature.gamemenu.GameMenuRow
import gg.padkit.PadKit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the handheld touch controls and the game menu in portrait and landscape and saves PNGs to
 * docs/previews, so the layout can be checked without a phone. The game picture is a stand-in.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class ClassicGamePadPreviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @Config(qualifiers = PORTRAIT)
    fun gbaPortrait() = render("gba-portrait.png", isLandscape = false, aspect = 3f / 2f, shoulders = true)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun gbaPortraitTop() =
        render("gba-portrait-top.png", isLandscape = false, aspect = 3f / 2f, shoulders = true, position = ClassicScreenPosition.TOP)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun gbaLandscape() = render("gba-landscape.png", isLandscape = true, aspect = 3f / 2f, shoulders = true)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun gbPortrait() = render("gb-portrait.png", isLandscape = false, aspect = 10f / 9f, shoulders = false)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun menuPortrait() = render("menu-portrait.png", isLandscape = false, aspect = 3f / 2f, shoulders = true, menu = true)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun menuLandscape() = render("menu-landscape.png", isLandscape = true, aspect = 3f / 2f, shoulders = true, menu = true)

    private fun render(
        name: String,
        isLandscape: Boolean,
        aspect: Float,
        shoulders: Boolean,
        size: ClassicScreenSize = ClassicScreenSize.FIT,
        position: ClassicScreenPosition = ClassicScreenPosition.MIDDLE,
        menu: Boolean = false,
    ) {
        val scene =
            javaClass.classLoader!!.getResourceAsStream("preview-scene.png").use { BitmapFactory.decodeStream(it) }
                .asImageBitmap()

        // The pad library keeps a loop running, so step the clock by hand instead of waiting for idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val gameArea = remember { mutableStateOf<Rect?>(null) }
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                gameArea.value?.let { area ->
                    with(density) {
                        Image(
                            bitmap = scene,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.offset(area.left.toDp(), area.top.toDp()).size(area.width.toDp(), area.height.toDp()),
                        )
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
                if (menu) {
                    // The menu window dims what is behind it.
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
                    SampleMenu()
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

    /** The game menu's first page as a Game Boy Advance game shows it. */
    @Composable
    private fun SampleMenu() {
        MaterialTheme(
            colorScheme = darkColorScheme(surface = Color(0xFF202127), onSurface = Color.White),
        ) {
            FloatingMenu(onDismiss = { }) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    GameMenuRow("Load state") { }
                    GameMenuRow("Save state") { }
                    GameMenuRow("Fast forward", value = "Off") { }
                    GameMenuRow("Cheats") { }
                    GameMenuRow("Screenshot") { }
                    GameMenuRow("Sound", value = "On") { }
                    GameMenuRow("Screen size", value = "Fit") { }
                    GameMenuRow("Screen position", value = "Center") { }
                    GameMenuRow("Edit controls") { }
                    GameMenuRow("Settings") { }
                    GameMenuRow("Restart") { }
                    GameMenuRow("Exit game") { }
                    GameMenuRow("Close") { }
                }
            }
        }
    }

    companion object {
        // The size of the phone in the reference screenshots.
        private const val PORTRAIT = "w412dp-h915dp-port-xhdpi"
        private const val LANDSCAPE = "w915dp-h412dp-land-xhdpi"
    }
}
