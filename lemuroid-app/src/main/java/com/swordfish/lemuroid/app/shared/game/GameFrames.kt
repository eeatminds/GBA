package com.swordfish.lemuroid.app.shared.game

import android.graphics.Bitmap
import kotlin.math.max

object GameFrames {
    /**
     * Enlarges or shrinks a game frame so its longest side is about [maxSize] pixels.
     * Small retro frames are enlarged by whole steps with sharp pixels, like on the real screen.
     */
    fun scaleToFit(
        frame: Bitmap,
        maxSize: Int,
    ): Bitmap {
        val longest = max(frame.width, frame.height)
        if (longest <= 0 || maxSize <= 0) return frame
        return if (longest < maxSize) {
            val factor = (maxSize / longest).coerceAtLeast(1)
            if (factor == 1) frame else Bitmap.createScaledBitmap(frame, frame.width * factor, frame.height * factor, false)
        } else {
            val ratio = maxSize.toFloat() / longest
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * ratio).toInt().coerceAtLeast(1),
                (frame.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
    }
}
