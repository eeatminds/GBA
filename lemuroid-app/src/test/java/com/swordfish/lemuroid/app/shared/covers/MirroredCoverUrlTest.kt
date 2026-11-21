package com.swordfish.lemuroid.app.shared.covers

import com.swordfish.lemuroid.lib.library.db.entity.Game
import org.junit.Assert.assertEquals
import org.junit.Test

class MirroredCoverUrlTest {
    @Test
    fun `libretro thumbnails covers are read from the GitHub mirror`() {
        assertEquals(
            "https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Color/master/Named_Boxarts/" +
                "Pokemon%20-%20Crystal%20Version%20%28USA%2C%20Europe%29%20%28Rev%201%29.png",
            Game.mirroredCoverUrl(
                "http://thumbnails.libretro.com/Nintendo - Game Boy Color/Named_Boxarts/Pokemon - Crystal Version (USA, Europe) (Rev 1).png",
            ),
        )
        // Anything else (a picture the player chose) stays as it is.
        assertEquals("content://media/cover.png", Game.mirroredCoverUrl("content://media/cover.png"))
    }
}
