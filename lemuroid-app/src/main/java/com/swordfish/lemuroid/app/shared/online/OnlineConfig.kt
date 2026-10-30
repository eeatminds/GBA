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

/** "Trade & battle" rooms link separate GBAs; "play together" rooms share one game. */
enum class RoomKind(val serverKey: String) {
    LINK("link"),
    NETPLAY("netplay"),
    ;

    companion object {
        fun fromServerKey(key: String?): RoomKind = values().firstOrNull { it.serverKey == key } ?: LINK
    }
}

/** Systems that support "play together" rooms, and how many players they allow. */
object NetplaySystems {
    private val maxPlayers =
        mapOf(
            SystemID.NES to 2,
            SystemID.SNES to 2,
            SystemID.GENESIS to 2,
            SystemID.SMS to 2,
            SystemID.GG to 2,
            SystemID.SEGACD to 2,
            SystemID.PC_ENGINE to 2,
            SystemID.ATARI2600 to 2,
            SystemID.ATARI7800 to 2,
            SystemID.PSX to 2,
            SystemID.N64 to 4,
            SystemID.FBNEO to 4,
            SystemID.MAME2003PLUS to 4,
        )

    val systemIds: List<String> = maxPlayers.keys.map { it.dbname }

    fun isSupported(systemId: String): Boolean = systemId in systemIds

    fun maxPlayers(systemId: String): Int = maxPlayers.entries.firstOrNull { it.key.dbname == systemId }?.value ?: 2
}

/** How far ahead inputs are sent. More delay hides more lag but makes controls feel slower. */
enum class InputDelay(val frames: Int, @StringRes val titleId: Int) {
    LOW(3, R.string.online_delay_low),
    NORMAL(5, R.string.online_delay_normal),
    HIGH(8, R.string.online_delay_high),
}

/** Everything the game screen needs to host or join a room. */
data class OnlineConfig(
    val serverUrl: String,
    val playerName: String,
    val isHost: Boolean,
    val roomCode: String?,
    val kind: RoomKind,
    val mode: LinkMode,
    val isPublic: Boolean,
    val gameTitle: String,
    val systemId: String,
    val coreName: String,
    val inputDelayFrames: Int,
    val maxPlayers: Int,
) : Serializable {
    /** Rooms only match players running the same core. */
    val protocol: String get() = if (kind == RoomKind.NETPLAY) "retrolink-netplay-1/$coreName" else ""

    companion object {
        /** Trade & battle rooms always use gpSP, the GBA core with network link support. */
        fun gpspCoreConfig(mode: LinkMode): SystemCoreConfig {
            val gba = GameSystem.findById(SystemID.GBA.dbname)
            val base = gba.systemCoreConfigs.first { it.coreID == CoreID.GPSP }
            return base.copy(
                defaultSettings = base.defaultSettings + CoreVariable("gpsp_serial", mode.coreValue),
            )
        }

        /** Play together rooms use the core the host picked, so everyone runs the same emulator. */
        fun netplayCoreConfig(
            systemId: String,
            coreName: String,
        ): SystemCoreConfig? {
            val system = GameSystem.findById(systemId)
            return system.systemCoreConfigs.firstOrNull { it.coreID.coreName == coreName }
        }
    }
}
