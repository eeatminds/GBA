package com.swordfish.lemuroid.app.shared.online.world

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.swordfish.lemuroid.app.mobile.feature.game.ClassicGameLayout
import com.swordfish.lemuroid.app.shared.online.OnlineRoomSheetContent
import com.swordfish.lemuroid.app.shared.online.OnlineSession
import com.swordfish.lemuroid.app.shared.online.RoomKind
import gg.padkit.PadKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

/**
 * The whole "same world" path on a real game: two Crystal players' work RAM (dumped from a
 * desktop emulator), read by the app's own code, the friend's position sent the way the server
 * relays it, then drawn over our real game screen, with "who is in front of us" for talking.
 *
 * Needs CRYSTAL_ROM (the player's own ROM) and WORLD_E2E (a folder with you.wram, you.png and
 * friend.wram); game data never goes into the repository. Pictures go to WORLD_E2E too.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w412dp-h915dp-port-xhdpi")
class SameWorldEndToEndTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `a friend in the same town shows on our screen and can be talked to`() {
        val folder = System.getenv("WORLD_E2E")?.let(::File)
        val romFile = System.getenv("CRYSTAL_ROM")?.let(::File)
        assumeTrue(folder != null && folder.exists() && romFile != null && romFile.exists())
        sameTown(folder!!, romFile!!, pictures = true)
    }

    // Gold (or Silver): the same, from WORLD_E2E/gold dumps.
    @Test
    fun `gold - a friend in the same town shows and can be talked to`() {
        val folder = System.getenv("WORLD_E2E")?.let { File(it, "gold") }
        val romFile = System.getenv("GOLD_ROM")?.let(::File)
        assumeTrue(folder != null && folder.exists() && romFile != null && romFile.exists())
        sameTown(folder!!, romFile!!, pictures = false)
    }

    private fun sameTown(
        folder: File,
        romFile: File,
        pictures: Boolean,
    ) {
        val adapter = WorldAdapters.forRom("gbc", readRom(romFile))!!
        val you = memory(File(folder, "you.wram"))
        val friend = adapter.read(memory(File(folder, "friend.wram")))!!

        // What the friend's phone sends, as the server passes it on (playing as Kris).
        val message =
            JSONObject()
                .put("t", "pos").put("from", 1).put("w", adapter.world).put("m", friend.map)
                .put("x", friend.x).put("y", friend.y).put("s", KRIS).put("f", friend.look.frame).put("h", 0)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val world = WorldSession(adapter, { you }, send = { }, scope = scope)
        world.onPresence(1, message)
        world.start()
        val deadline = System.currentTimeMillis() + 5_000
        while ((world.view.value.local == null || world.view.value.others.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        // We stand at the house door facing down; the friend is one step below.
        assertEquals("24.4", world.view.value.local?.map)
        assertEquals(1, world.playerInFront())
        assertEquals(WorldSession.Spot.Here("New Bark Town"), world.spotOf(1))
        assertEquals("New Bark Town", world.ourPlace())
        // New Bark Town's neighbours: Route 29 (60 steps wide) to the west, Route 27 to the east.
        val neighbors = world.view.value.neighbors
        val route29 = neighbors.keys.single { adapter.place(it)?.name == "Route 29" }
        val route27 = neighbors.keys.single { adapter.place(it)?.name == "Route 27" }
        assertEquals(MapOffset(-60 * 16, 0), neighbors[route29])
        assertEquals(MapOffset(20 * 16, 0), neighbors[route27])
        // A friend standing at Route 29's east end is one step west of our map.
        val onRoute29 = world.view.value.onOurMap(friend.copy(map = route29, x = 59 * 16, y = 6 * 16))
        assertEquals(-16, onRoute29?.x)
        // The game itself can trade and battle right here.
        assertEquals(true, world.canLinkInPlace)
        if (!pictures) {
            world.stop()
            scope.cancel()
            return
        }

        val frame = BitmapFactory.decodeFile(File(folder, "you.png").path).asImageBitmap()
        val talk = mutableStateOf<Talk?>(null)
        show(world, frame, talk)
        
        capture(File(folder, "e2e-friend.png"))
        talk.value = Talk.Ask(1, "Misty")
        capture(File(folder, "e2e-talk.png"))
        talk.value = Talk.Invited(1, "Misty", Talk.BATTLE)
        capture(File(folder, "e2e-invited.png"))
        talk.value = Talk.Result("Misty", Talk.TRADE, accepted = true)
        capture(File(folder, "e2e-linked.png"))
        talk.value = null
        panel.value = true
        capture(File(folder, "e2e-panel.png"))
        world.stop()
        scope.cancel()
        GameViewport.rect.value = null
    }

    private val panel = mutableStateOf(false)

    private fun show(
        world: WorldSession,
        frame: androidx.compose.ui.graphics.ImageBitmap,
        talk: MutableState<Talk?>,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val density = LocalDensity.current
                val area by GameViewport.rect.collectAsState()
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    area?.let { rect ->
                        with(density) {
                            Image(
                                bitmap = frame,
                                contentDescription = null,
                                contentScale = ContentScale.FillBounds,
                                filterQuality = FilterQuality.None,
                                modifier = Modifier.offset(rect.left.toDp(), rect.top.toDp()).size(rect.width.toDp(), rect.height.toDp()),
                            )
                        }
                    }
                    PadKit(modifier = Modifier.fillMaxSize(), onInputEvents = { }) {
                        ClassicGameLayout(
                            isLandscape = false,
                            gameAspectRatio = 10f / 9f,
                            nativeWidth = 160,
                            nativeHeight = 144,
                            hasShoulderButtons = false,
                            showControls = true,
                            settings = null,
                            onGameAreaPositioned = { GameViewport.rect.value = it },
                        )
                    }
                    WorldOverlay(world, mapOf(1 to "Misty"))
                    talk.value?.let { TalkBox(it, isPokemon = true, onAsk = { _, _ -> }, onAnswer = { _, _ -> }, onClose = { }) }
                    if (panel.value) {
                        // The Players panel is a dialog (its own window); draw its insides the same way here.
                        val room =
                            OnlineSession.State.InRoom(
                                code = "BE4YED", clientId = 0, maxPlayers = 8, kind = RoomKind.LINK,
                                players = listOf(OnlineSession.Player(0, "Ash"), OnlineSession.Player(1, "Misty")),
                                groups = listOf(listOf(OnlineSession.LinkMember(0, 0), OnlineSession.LinkMember(1, 1))),
                            )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                            Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(24.dp)) {
                                Column(Modifier.padding(24.dp)) {
                                    Text("Room BE4YED", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 16.dp))
                                    OnlineRoomSheetContent(room, hasLinks = true, isPokemon = true, onLink = { }, onUnlink = { }, world = world)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun capture(out: File) {
        repeat(20) { compose.mainClock.advanceTimeByFrame() }
        var bitmap: Bitmap? = null
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        out.outputStream().use { bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun memory(file: File) = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ByteBuffer.wrap(file.readBytes()))))

    private fun readRom(file: File): ByteArray {
        if (!file.name.endsWith(".zip", ignoreCase = true)) return file.readBytes()
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".gbc", ignoreCase = true) || it.name.endsWith(".gb", ignoreCase = true) }
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }

    private companion object {
        // Kris on foot, blue palette.
        const val KRIS = 0x60 or (1 shl 8)
    }
}
