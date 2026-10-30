package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.entity.Game
import com.swordfish.lemuroid.lib.storage.DirectoriesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * A game's battery save ("in-game save") as a file the player can move around.
 * The app stores it as "<game file name>.srm", the raw save data, which is the same format as
 * the .sav files most other emulators use, so saves can move between apps.
 */
class GameSaveFiles(private val directoriesManager: DirectoriesManager) {
    fun hasSave(game: Game): Boolean {
        val file = saveFile(game)
        return file.exists() && file.length() > 0
    }

    /** Suggested name when exporting, e.g. "My Game.sav". */
    fun exportName(game: Game): String = "${game.fileName.substringBeforeLast(".")}.sav"

    suspend fun export(
        context: Context,
        game: Game,
        target: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = saveFile(game).readBytes()
                context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(data) }
            }.onFailure { Timber.e(it, "Cannot export save") }.isSuccess
        }

    suspend fun import(
        context: Context,
        game: Game,
        source: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val data =
                    context.contentResolver.openInputStream(source)!!.use { input ->
                        val bytes = input.readBytes()
                        require(bytes.isNotEmpty() && bytes.size <= MAX_SAVE_SIZE) { "Not a save file" }
                        bytes
                    }
                val file = saveFile(game)
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, "${file.name}.import")
                temporary.writeBytes(data)
                if (!temporary.renameTo(file)) {
                    file.writeBytes(data)
                    temporary.delete()
                }
                // A newer auto-save would bring back the old progress, so it goes.
                deleteAutoSaves(game)
            }.onFailure { Timber.e(it, "Cannot import save") }.isSuccess
        }

    suspend fun erase(game: Game): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                saveFile(game).delete()
                deleteAutoSaves(game)
            }.isSuccess
        }

    private fun deleteAutoSaves(game: Game) {
        val statesRoot = directoriesManager.getStatesDirectory()
        statesRoot.listFiles()?.filter { it.isDirectory }?.forEach { coreFolder ->
            coreFolder.listFiles()
                ?.filter { it.name.startsWith("${game.fileName}.state") }
                ?.forEach { it.delete() }
        }
    }

    private fun saveFile(game: Game): File =
        File(directoriesManager.getSavesDirectory(), "${game.fileName.substringBeforeLast(".")}.srm")

    companion object {
        // Biggest battery saves are a few hundred KB (N64 Flash RAM); 4 MB leaves room to spare.
        private const val MAX_SAVE_SIZE = 4 * 1024 * 1024
    }
}
