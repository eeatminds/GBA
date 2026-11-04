package com.swordfish.lemuroid.app.shared.online

import com.swordfish.lemuroid.app.shared.online.world.WorldSession
import org.junit.Assert.assertEquals
import org.junit.Test

class CoreLinkLogTest {
    @Test
    fun `a cable that connected with the fixes on`() {
        val lines =
            listOf(
                "Starting GameLink network client on 0.0.0.0:0",
                "Error connecting to server: Connection refused",
                "Starting GameLink network client on 127.0.0.1:56400",
                "GameLink network client connected to server!",
                "Link: whole-block exchanges for PM_CRYSTAL",
            )
        assertEquals("fixes on · cable connected", CoreLinkLog.summarize(lines))
    }

    @Test
    fun `the latest start counts, and its error is shown`() {
        val lines =
            listOf(
                "Starting GameLink network client on 127.0.0.1:56400",
                "GameLink network client connected to server!",
                "Starting GameLink network client on 127.0.0.1:56401",
                "Error connecting to server: Connection refused",
            )
        assertEquals("fixes off · cable: Error connecting to server: Connection refused", CoreLinkLog.summarize(lines))
    }

    @Test
    fun `a cable that never started`() {
        assertEquals("cable never started", CoreLinkLog.summarize(listOf("something else")))
    }

    @Test
    fun `details put the script, the core and the bridge on one line`() {
        val stats = GbLinkBridge.AttemptStats(gameConnected = true, blocksFromGame = 1, blocksFromPeer = 0, blocksLost = 0)
        assertEquals(
            "script ran · fixes on · cable connected · bridge on · sent 1 · got 0",
            linkDetails(WorldSession.LinkStatus.Failed(started = true), stats, "fixes on · cable connected"),
        )
        assertEquals("script waited", linkDetails(WorldSession.LinkStatus.Failed(started = false), null, null))
    }
}
