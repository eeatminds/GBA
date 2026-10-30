package com.swordfish.lemuroid.app.shared.online

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.swordfish.lemuroid.BuildConfig

/** Player name and server address for online rooms, stored on the device. */
class OnlineSettings(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var playerName: String
        get() = preferences.getString(KEY_PLAYER_NAME, null).orEmpty()
        set(value) = preferences.edit().putString(KEY_PLAYER_NAME, value.trim().take(MAX_NAME_LENGTH)).apply()

    /** The relay server, e.g. "https://rooms.example.com". Empty when none is set up. */
    var serverUrl: String
        get() = normalizeServerUrl(preferences.getString(KEY_SERVER_URL, null) ?: BuildConfig.ONLINE_SERVER_URL)
        set(value) = preferences.edit().putString(KEY_SERVER_URL, normalizeServerUrl(value)).apply()

    fun resetServerUrl() {
        preferences.edit().remove(KEY_SERVER_URL).apply()
    }

    val isServerConfigured: Boolean
        get() = serverUrl.isNotBlank()

    companion object {
        private const val PREFERENCES_NAME = "online_settings"
        private const val KEY_PLAYER_NAME = "player_name"
        private const val KEY_SERVER_URL = "server_url"
        const val MAX_NAME_LENGTH = 20

        fun normalizeServerUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            if (trimmed.isBlank()) return ""
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        }

        fun webSocketUrl(serverUrl: String): String {
            val base = normalizeServerUrl(serverUrl)
            return when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/ws"
                else -> "ws://" + base.removePrefix("http://") + "/ws"
            }
        }

        fun inviteLink(serverUrl: String, code: String): String = "${normalizeServerUrl(serverUrl)}/r/$code"

        /** Finds a room code in an invite link ("https://host/r/ABC123", "retrolink://join/ABC123") or plain text. */
        fun parseRoomCode(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val uri = runCatching { Uri.parse(text.trim()) }.getOrNull()
            val candidate =
                when {
                    uri?.scheme == BuildConfig.ONLINE_LINK_SCHEME -> uri.lastPathSegment
                    uri?.scheme == "https" || uri?.scheme == "http" -> {
                        val segments = uri.pathSegments
                        val index = segments.indexOfFirst { it == "r" || it == "join" }
                        if (index >= 0) segments.getOrNull(index + 1) else null
                    }
                    else -> text
                }
            val code = candidate?.trim()?.uppercase()?.filter { it.isLetterOrDigit() } ?: return null
            return code.takeIf { it.length == ROOM_CODE_LENGTH }
        }

        const val ROOM_CODE_LENGTH = 6
    }
}
