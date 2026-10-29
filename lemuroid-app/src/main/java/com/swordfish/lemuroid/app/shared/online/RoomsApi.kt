package com.swordfish.lemuroid.app.shared.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RoomInfo(
    val code: String,
    val game: String,
    val host: String,
    val mode: LinkMode,
    val players: Int,
    val maxPlayers: Int,
    val isPublic: Boolean,
) {
    companion object {
        fun fromJson(json: JSONObject): RoomInfo {
            return RoomInfo(
                code = json.optString("code"),
                game = json.optString("game"),
                host = json.optString("host"),
                mode = LinkMode.fromServerKey(json.optString("mode")),
                players = json.optInt("players"),
                maxPlayers = json.optInt("maxPlayers"),
                isPublic = json.optBoolean("public"),
            )
        }
    }
}

/** HTTP calls to the relay server: public room list, room lookup and reports. */
class RoomsApi(private val client: OkHttpClient = defaultClient) {
    suspend fun listPublicRooms(serverUrl: String): List<RoomInfo> =
        withContext(Dispatchers.IO) {
            val body = get("${OnlineSettings.normalizeServerUrl(serverUrl)}/api/rooms?system=gba")
            val rooms = JSONObject(body).optJSONArray("rooms") ?: return@withContext emptyList()
            (0 until rooms.length()).map { RoomInfo.fromJson(rooms.getJSONObject(it)) }
        }

    /** Returns null when the room does not exist. */
    suspend fun findRoom(
        serverUrl: String,
        code: String,
    ): RoomInfo? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("${OnlineSettings.normalizeServerUrl(serverUrl)}/api/rooms/$code").build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                RoomInfo.fromJson(JSONObject(response.body!!.string()).getJSONObject("room"))
            }
        }

    suspend fun reportRoom(
        serverUrl: String,
        code: String,
        reason: String,
    ) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("code", code).put("reason", reason).toString()
        val request =
            Request.Builder()
                .url("${OnlineSettings.normalizeServerUrl(serverUrl)}/api/report")
                .post(json.toRequestBody("application/json".toMediaType()))
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            return response.body!!.string()
        }
    }

    companion object {
        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }
}
