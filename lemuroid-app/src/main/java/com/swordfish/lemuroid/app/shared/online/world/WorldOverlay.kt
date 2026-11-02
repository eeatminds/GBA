package com.swordfish.lemuroid.app.shared.online.world

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** Where the game picture is drawn on screen, in pixels (set by the game screen layout). */
object GameViewport {
    val rect = MutableStateFlow<Rect?>(null)
}

/**
 * Draws the other players who are on our map, with this game's own sprites, over the game
 * picture. Works at any screen size: everything is scaled from the game's own pixels.
 */
@Composable
fun WorldOverlay(
    session: WorldSession,
    names: Map<Int, String>,
) {
    val view by session.view.collectAsState()
    val viewport by GameViewport.rect.collectAsState()
    val area = viewport ?: return
    val local = view.local ?: return
    val visible = view.others.filterValues { it.current.map == local.map }
    if (visible.isEmpty()) return

    // Redraw every frame so others glide smoothly between their position updates.
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now = SystemClock.uptimeMillis() }
    }

    val adapter = session.adapter
    val bitmaps = remember(adapter) { HashMap<Pair<Int, Int>, ImageBitmap?>() }
    fun bitmap(look: Look): ImageBitmap? =
        bitmaps.getOrPut(look.sprite to look.frame) {
            adapter.sprite(look)?.let { picture ->
                Bitmap.createBitmap(picture.pixels, picture.width, picture.height, Bitmap.Config.ARGB_8888).asImageBitmap()
            }
        }

    val density = LocalDensity.current
    val labelPaint =
        remember(density) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = with(density) { 11f * this.density }
                setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK)
            }
        }

    with(density) {
        Canvas(
            modifier =
                Modifier
                    .offset { IntOffset(area.left.roundToInt(), area.top.roundToInt()) }
                    .size(area.width.toDp(), area.height.toDp())
                    .clipToBounds(),
        ) {
            val scale = size.width / adapter.screenWidth
            // Our own sprite: everyone else stands relative to its feet.
            val ourPicture = adapter.sprite(local.look)
            val ourFeetX = local.screenX + (ourPicture?.width ?: 16) / 2
            val ourFeetY = local.screenY + (ourPicture?.height ?: 32)

            val drawn = visible.map { (id, remote) -> id to remote.at(now) }.sortedBy { it.second.y }
            for ((id, avatar) in drawn) {
                val image = bitmap(avatar.look) ?: continue
                val feetX = ourFeetX + (avatar.x - local.x)
                val feetY = ourFeetY + (avatar.y - local.y)
                val left = (feetX - image.width / 2) * scale
                val top = (feetY - image.height) * scale
                val width = image.width * scale
                val height = image.height * scale
                val pivot = Offset(left + width / 2, top + height / 2)
                scale(scaleX = if (avatar.look.flip) -1f else 1f, scaleY = 1f, pivot = pivot) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(width.roundToInt(), height.roundToInt()),
                        filterQuality = FilterQuality.None,
                    )
                }
                names[id]?.let { name ->
                    drawContext.canvas.nativeCanvas.drawText(name, left + width / 2, top - 4f, labelPaint)
                }
            }
        }
    }
}
