package com.swordfish.lemuroid.app.shared.online

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swordfish.lemuroid.app.mobile.feature.game.ClassicGameLayout
import com.swordfish.lemuroid.app.shared.online.OnlineSession.LinkMember
import com.swordfish.lemuroid.app.shared.online.OnlineSession.Player
import com.swordfish.lemuroid.app.shared.online.OnlineSession.State
import com.swordfish.lemuroid.app.shared.online.world.GameViewport
import gg.padkit.PadKit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Draws the game screen with an online room's status, the loading card and the room panel, and
 * saves PNGs to docs/previews, to check that nothing covers the game or its menu button.
 * The game picture is a stand-in.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class OnlineRoomPreviewTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val alone =
        State.InRoom(code = "NP3PEB", clientId = 0, players = listOf(Player(0, "Ash")), maxPlayers = 8, kind = RoomKind.LINK)

    private val linked =
        alone.copy(
            players = listOf(Player(0, "Ash"), Player(1, "Misty")),
            groups = listOf(listOf(LinkMember(0, 0), LinkMember(1, 1))),
        )

    private val notLinked =
        alone.copy(players = listOf(Player(0, "Ash"), Player(1, "Misty"), Player(2, "Brock")))

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun gbLandscapeAlone() = render("online-gb-landscape.png", isLandscape = true, gameBoy = true, state = alone)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun gbaLandscapeLinked() = render("online-gba-landscape.png", isLandscape = true, gameBoy = false, state = linked)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun gbPortraitNotLinked() = render("online-gb-portrait.png", isLandscape = false, gameBoy = true, state = notLinked)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun loading() = render("online-loading.png", isLandscape = false, gameBoy = true, state = State.Connecting, isCreator = false)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun roomPanel() = render("online-room-panel.png", isLandscape = false, gameBoy = true, state = linked, panel = true)

    private fun render(
        name: String,
        isLandscape: Boolean,
        gameBoy: Boolean,
        state: State,
        isCreator: Boolean = true,
        panel: Boolean = false,
    ) {
        val scene =
            javaClass.classLoader!!.getResourceAsStream("preview-scene.png").use { BitmapFactory.decodeStream(it) }
                .asImageBitmap()

        // The pad library keeps a loop running, so step the clock by hand instead of waiting for idle.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val density = LocalDensity.current
                val gameArea by GameViewport.rect.collectAsState()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    gameArea?.let { area ->
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
                            gameAspectRatio = if (gameBoy) 10f / 9f else 3f / 2f,
                            nativeWidth = if (gameBoy) 160 else 240,
                            nativeHeight = if (gameBoy) 144 else 160,
                            hasShoulderButtons = !gameBoy,
                            showControls = true,
                            settings = null,
                            onGameAreaPositioned = { GameViewport.rect.value = it },
                            onGameSpacePositioned = { GameViewport.area.value = it },
                        )
                    }
                    OnlineRoomStatus(
                        state = state,
                        hasLinks = true,
                        isCreator = isCreator,
                        roomCode = "NP3PEB",
                        isPokemon = true,
                        onOpenRoom = { },
                    )
                    if (panel && state is State.InRoom) {
                        // The panel is a dialog (its own window); draw its insides the same way here.
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(24.dp)) {
                                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Text("Room ${state.code}", style = MaterialTheme.typography.headlineSmall)
                                    OnlineRoomSheetContent(state, hasLinks = true, isPokemon = true, onLink = { }, onUnlink = { })
                                    Text("Invite        Close", modifier = Modifier.align(Alignment.End), color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        // Draw the window straight into a bitmap (the test screenshot helper waits for a redraw forever).
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val folder = File(System.getProperty("previews.output") ?: "build/previews").apply { mkdirs() }
        File(folder, name).outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        GameViewport.rect.value = null
        GameViewport.area.value = null
    }

    companion object {
        // The size of the phone in the reference screenshots.
        private const val PORTRAIT = "w412dp-h915dp-port-xhdpi"
        private const val LANDSCAPE = "w915dp-h412dp-land-xhdpi"
    }
}
