package com.swordfish.lemuroid.app.shared.online

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * Phones whose Gambatte link patches differ can't link (one waits for a message the other never
 * sends), so a room keeps them apart by the link protocol name. This fails when the patch changed
 * but the name didn't: change [OnlineConfig.GB_LINK_PROTOCOL], then update [PATCH_SHA256] here.
 */
class GbLinkProtocolTest {
    @Test
    fun `the link protocol name changes with the Gambatte link patch`() {
        val root = File(System.getProperty("user.dir")).let { if (File(it, "extra-cores").exists()) it else it.parentFile }
        val patch = File(root, "extra-cores/gambatte-link/0001-link-whole-blocks-and-sync-over-the-network.patch")
        val sha256 = MessageDigest.getInstance("SHA-256").digest(patch.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals(
            "The Gambatte link patch changed: give OnlineConfig.GB_LINK_PROTOCOL a new name, then update PATCH_SHA256",
            PATCH_SHA256 to PROTOCOL,
            sha256 to OnlineConfig.GB_LINK_PROTOCOL,
        )
    }

    companion object {
        private const val PATCH_SHA256 = "6f372dbb63b0f16b1301868702b912867874d594e511a717573d321c71810ca2"
        private const val PROTOCOL = "retrolink-gblink-3"
    }
}
