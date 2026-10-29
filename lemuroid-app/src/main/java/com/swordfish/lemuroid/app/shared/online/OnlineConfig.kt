package com.swordfish.lemuroid.app.shared.online

import androidx.annotation.StringRes
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.lib.core.CoreVariable
import com.swordfish.lemuroid.lib.library.CoreID
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.library.SystemCoreConfig
import com.swordfish.lemuroid.lib.library.SystemID
import java.io.Serializable

/**
 * How the emulated GBAs are connected. Every player in a room must use the same mode,
 * so the host picks it and joiners inherit it. Values map to gpSP's "gpsp_serial" option
 * and to the mode keys used by the relay server.
 */
enum class LinkMode(
    val serverKey: String,
    val coreValue: String,
    @StringRes val titleId: Int,
    @StringRes val descriptionId: Int,
    val maxPlayers: Int,
) {
    LINK_CABLE(
        "poke",
        "mul_poke",
        R.string.online_mode_link_cable,
        R.string.online_mode_link_cable_description,
        4,
    ),
    WIRELESS(
        "rfu",
        "rfu",
        R.string.online_mode_wireless,
        R.string.online_mode_wireless_description,
        5,
    ),
    AUTO(
        "auto",
        "auto",
        R.string.online_mode_auto,
        R.string.online_mode_auto_description,
        4,
    ),
    ADVANCE_WARS(
        "aw1",
        "mul_aw1",
        R.string.online_mode_advance_wars,
        R.string.online_mode_advance_wars_description,
        4,
    ),
    ADVANCE_WARS_2(
        "aw2",
        "mul_aw2",
        R.string.online_mode_advance_wars_2,
        R.string.online_mode_advance_wars_description,
        4,
    ),
    ;

    companion object {
        fun fromServerKey(key: String?): LinkMode = values().firstOrNull { it.serverKey == key } ?: AUTO
    }
}

/** Everything the game screen needs to host or join a room. */
data class OnlineConfig(
    val serverUrl: String,
    val playerName: String,
    val isHost: Boolean,
    val roomCode: String?,
    val mode: LinkMode,
    val isPublic: Boolean,
    val gameTitle: String,
) : Serializable {
    companion object {
        /** Online rooms always use gpSP, the GBA core with network link support. */
        fun gpspCoreConfig(mode: LinkMode): SystemCoreConfig {
            val gba = GameSystem.findById(SystemID.GBA.dbname)
            val base = gba.systemCoreConfigs.first { it.coreID == CoreID.GPSP }
            return base.copy(
                defaultSettings = base.defaultSettings + CoreVariable("gpsp_serial", mode.coreValue),
            )
        }
    }
}
