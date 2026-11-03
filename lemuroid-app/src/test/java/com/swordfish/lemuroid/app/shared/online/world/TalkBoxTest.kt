package com.swordfish.lemuroid.app.shared.online.world

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swordfish.lemuroid.app.shared.game.BaseGameScreenViewModel.MenuKey
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The talk box answers the game's buttons: the pad moves the arrow, A picks, B backs out. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w412dp-h915dp-port-xhdpi")
class TalkBoxTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val keys = MutableSharedFlow<MenuKey>(extraBufferCapacity = 8)
    private val talk = mutableStateOf<Talk?>(null)
    private val picked = ArrayList<String>()

    @After
    fun tearDown() {
        GameViewport.rect.value = null
    }

    private fun show(first: Talk) {
        GameViewport.rect.value = Rect(0f, 300f, 1236f, 1412f)
        talk.value = first
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF406040))) {
                talk.value?.let { current ->
                    TalkBox(
                        talk = current,
                        isPokemon = true,
                        onAsk = { _, kind -> picked += "ask $kind" },
                        onAnswer = { _, accept -> picked += if (accept) "accept" else "decline" },
                        onClose = { picked += "close" },
                        keys = keys,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(vararg pressed: MenuKey) {
        pressed.forEach {
            keys.tryEmit(it)
            compose.waitForIdle()
        }
    }

    @Test
    fun `right then A picks battle, and B closes`() {
        show(Talk.Ask(1, "Gold"))
        capture("talk-ask.png")
        press(MenuKey.RIGHT, MenuKey.A)
        assertEquals(listOf("ask battle"), picked)
        press(MenuKey.B)
        assertEquals(listOf("ask battle", "close"), picked)
    }

    @Test
    fun `the arrow stops at the ends`() {
        show(Talk.Ask(1, "Gold"))
        press(MenuKey.LEFT, MenuKey.RIGHT, MenuKey.RIGHT, MenuKey.RIGHT, MenuKey.A)
        assertEquals(listOf("close"), picked)
    }

    @Test
    fun `an invitation is accepted with A and declined with B`() {
        show(Talk.Invited(1, "Silver", Talk.TRADE))
        capture("talk-invited.png")
        press(MenuKey.A)
        assertEquals(listOf("accept"), picked)
        talk.value = Talk.Invited(1, "Silver", Talk.BATTLE)
        compose.waitForIdle()
        press(MenuKey.B)
        assertEquals(listOf("accept", "decline"), picked)
    }

    // Pictures for a look at the layout, when WORLD_E2E names a folder (never in the repo).
    private fun capture(name: String) {
        val folder = System.getenv("WORLD_E2E")?.let(::File)?.takeIf { it.isDirectory } ?: return
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
