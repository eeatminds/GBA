package com.swordfish.lemuroid.app.shared.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.MessageDigest

/**
 * Copies each game's in-game save to a folder the player picked once (on the phone, an SD
 * card, or Google Drive through Android's file picker), as "<game>.sav". Nothing goes to our
 * servers. The copy is only written when the save changed.
 */
object SaveBackup {
    private const val PREFERENCES = "save_backup"
    private const val KEY_FOLDER = "folder"
    private const val KEY_HASH_PREFIX = "hash_"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun folder(context: Context): Uri? = preferences(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun folderName(context: Context): String? {
        val folder = folder(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, folder)?.name }.getOrNull() ?: folder.lastPathSegment
    }

    /** Uses [uri] (from ACTION_OPEN_DOCUMENT_TREE) from now on, or stops backing up when null. */
    fun setFolder(
        context: Context,
        uri: Uri?,
    ) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previous = folder(context)
        if (previous != null && previous != uri) {
            runCatching { resolver.releasePersistableUriPermission(previous, flags) }
        }
        if (uri != null) resolver.takePersistableUriPermission(uri, flags)
        val editor = preferences(context).edit()
        if (uri == null) editor.remove(KEY_FOLDER) else editor.putString(KEY_FOLDER, uri.toString())
        // A new folder starts empty, so every game gets copied again.
        preferences(context).all.keys.filter { it.startsWith(KEY_HASH_PREFIX) }.forEach { editor.remove(it) }
        editor.apply()
    }

    /** File name of [game]'s backup, the same as an exported save. */
    fun fileName(game: Game): String = "${game.fileName.substringBeforeLast(".")}.sav"

    suspend fun backup(
        context: Context,
        game: Game,
        sram: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val folderUri = folder(context) ?: return@withContext
        if (sram.isEmpty()) return@withContext

        val hash = sha1(sram)
        val hashKey = KEY_HASH_PREFIX + game.id
        if (preferences(context).getString(hashKey, null) == hash) return@withContext

        runCatching {
            val folder = DocumentFile.fromTreeUri(context, folderUri) ?: error("Backup folder is gone")
            val name = fileName(game)
            val file = folder.findFile(name) ?: folder.createFile("application/octet-stream", name) ?: error("Cannot create $name")
            context.contentResolver.openOutputStream(file.uri, "wt")!!.use { it.write(sram) }
            preferences(context).edit().putString(hashKey, hash).apply()
        }.onFailure { Timber.w(it, "Could not back up the save of ${game.title}") }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
}
