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
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/** Where the game picture is drawn on screen, in pixels (set by the game screen layout). */
object GameViewport {
    val rect = MutableStateFlow<Rect?>(null)

    // The space the game picture sits in (the picture keeps its shape, so it may not fill it).
    // Null with layouts that don't report it.
    val area = MutableStateFlow<Rect?>(null)
}

/**
 * Draws the other players who are on our map or a map joined to it, with this game's own
 * sprites, over the game picture. Works at any screen size: everything is scaled from the
 * game's own pixels.
 *
 * While anyone is nearby it follows the game every frame: our own position is read fresh each
 * frame (so others stay put against the scrolling map), others are played back from
 * [WorldSession.Remote], and everything lands on whole game pixels. A frame that looks the
 * same as the last one isn't drawn again.
 */
@Composable
fun WorldOverlay(
    session: WorldSession,
    names: Map<Int, String>,
) {
    val view by session.view.collectAsState()
    val viewport by GameViewport.rect.collectAsState()
    val area = viewport ?: return
    // Anyone on our map, or on a joined map (they show up at its edge, as in the game)?
    val nearby = view.local != null && view.others.values.any { view.onOurMap(it.current) != null }
    if (!nearby) return

    var frame by remember { mutableStateOf<OverlayFrame?>(null) }
    LaunchedEffect(session) {
        while (isActive) {
            withFrameMillis {
                val current = session.view.value
                val local = session.readLocal() ?: current.local
                frame = local?.let { overlayFrame(current, it, SystemClock.uptimeMillis()) }
            }
        }
    }
    val shown = frame ?: return
    val local = shown.local
    // A menu or text box is open: don't draw others over it.
    if (local.covered || shown.players.isEmpty()) return

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

            for (player in shown.players) {
                val image = bitmap(player.look) ?: continue
                // Whole game pixels, like everything else the game draws.
                val feetX = ourFeetX + (player.x - local.x)
                val feetY = ourFeetY + (player.y - local.y)
                val left = ((feetX - image.width / 2) * scale).roundToInt()
                val top = ((feetY - image.height) * scale).roundToInt()
                val width = (image.width * scale).roundToInt()
                val height = (image.height * scale).roundToInt()
                val pivot = Offset(left + width / 2f, top + height / 2f)
                scale(scaleX = if (player.look.flip) -1f else 1f, scaleY = 1f, pivot = pivot) {
                    drawImage(
                        image = image,
                        dstOffset = IntOffset(left, top),
                        dstSize = IntSize(width, height),
                        filterQuality = FilterQuality.None,
                    )
                }
                names[player.id]?.let { name ->
                    drawContext.canvas.nativeCanvas.drawText(name, left + width / 2f, top - 4f, labelPaint)
                }
            }
        }
    }
}

/** One overlay picture: us, and the others on our map (in our map's coordinates, back to front). */
private data class OverlayFrame(val local: Avatar, val players: List<Placed>)

private data class Placed(val id: Int, val x: Int, val y: Int, val look: Look)

private fun overlayFrame(
    view: WorldSession.View,
    local: Avatar,
    now: Long,
): OverlayFrame =
    OverlayFrame(
        // Only what decides the picture, so a still frame equals the last one.
        local.copy(facing = null),
        view.others.mapNotNull { (id, remote) ->
            view.onMap(remote.at(now), local)?.let { Placed(id, it.x, it.y, it.look) }
        }.sortedBy { it.y },
    )
