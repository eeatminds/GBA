package com.swordfish.lemuroid.app.shared.online

import com.swordfish.lemuroid.app.shared.online.world.LinkFailure
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkFailureTest {
    private fun stats(
        fromGame: Int,
        fromPeer: Int,
    ) = GbLinkBridge.AttemptStats(gameConnected = true, blocksFromGame = fromGame, blocksFromPeer = fromPeer, blocksLost = 0)

    @Test
    fun `a game that never got to it was busy`() {
        assertEquals(LinkFailure.BUSY, linkFailure(started = false, stats = stats(0, 1)))
    }

    @Test
    fun `a script that never got into the game is our game's problem`() {
        assertEquals(LinkFailure.OUR_GAME, linkFailure(started = false, stats = stats(0, 0), queued = false))
    }

    @Test
    fun `our game sending nothing means it isn't on the link`() {
        assertEquals(LinkFailure.OUR_GAME, linkFailure(started = true, stats = stats(0, 1)))
    }

    @Test
    fun `nothing from their game means it didn't answer`() {
        assertEquals(LinkFailure.NO_ANSWER, linkFailure(started = true, stats = stats(1, 0)))
    }

    @Test
    fun `both sent but it failed later`() {
        assertEquals(LinkFailure.DROPPED, linkFailure(started = true, stats = stats(2, 1)))
        assertEquals(LinkFailure.DROPPED, linkFailure(started = true, stats = null))
    }
}
