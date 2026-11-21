package com.swordfish.lemuroid.app.shared.cheats

import android.content.Context
import com.swordfish.lemuroid.lib.library.db.entity.Game
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.Serializable

data class Cheat(
    val name: String,
    val code: String,
    val enabled: Boolean,
) : Serializable {
    /**
     * Libretro cores take one code per cheat, with lines joined by "+".
     * Players type one code per line, like GameShark or Action Replay codes are usually written.
     */
    val coreCode: String
        get() =
            code.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("+")
}

/** Cheats are stored per game, on the device, in the app's private files. */
class CheatsManager(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "cheats")

    fun getCheats(game: Game): List<Cheat> {
        val file = fileFor(game)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Cheat(
                    name = item.optString("name"),
                    code = item.optString("code"),
                    enabled = item.optBoolean("enabled", true),
                )
            }
        }.onFailure { Timber.e(it, "Cannot read cheats for ${game.fileName}") }.getOrDefault(emptyList())
    }

    fun setCheats(
        game: Game,
        cheats: List<Cheat>,
    ) {
        directory.mkdirs()
        val array = JSONArray()
        cheats.forEach {
            array.put(JSONObject().put("name", it.name).put("code", it.code).put("enabled", it.enabled))
        }
        fileFor(game).writeText(array.toString())
    }

    private fun fileFor(game: Game): File {
        val safeName = game.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        return File(directory, "$safeName.json")
    }
}
