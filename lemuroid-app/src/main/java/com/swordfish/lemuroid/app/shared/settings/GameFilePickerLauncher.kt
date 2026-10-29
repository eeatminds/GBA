package com.swordfish.lemuroid.app.shared.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
import com.swordfish.lemuroid.lib.library.GameSystem
import com.swordfish.lemuroid.lib.storage.local.PickedGameFiles
import timber.log.Timber

/**
 * "Add a game": opens the system file picker (starting in Downloads), keeps read access to
 * the chosen files and adds them to the library. No storage permission is needed.
 */
class GameFilePickerLauncher : ComponentActivity() {
    private val pickFiles =
        registerForActivityResult(OpenGameFiles()) { uris ->
            if (uris.isNotEmpty()) addGames(uris)
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            try {
                pickFiles.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                Timber.e(e, "No file picker available")
                Toast.makeText(this, R.string.add_game_picker_missing, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun addGames(picked: List<Uri>) {
        // Only keep files the app can actually open, and say which ones were skipped.
        val (uris, rejected) = picked.partition { isPlayableFile(displayName(it)) }
        if (rejected.isNotEmpty()) {
            val names = rejected.joinToString(", ") { displayName(it) ?: "?" }
            Toast.makeText(this, getString(R.string.add_game_not_supported, names), Toast.LENGTH_LONG).show()
        }

        val kept =
            uris.filter { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.onFailure { Timber.w(it, "Cannot keep access to $uri") }.isSuccess
            }
        if (kept.isEmpty()) return

        PickedGameFiles.add(applicationContext, kept)
        LibraryIndexScheduler.scheduleLibrarySync(applicationContext)
        val message = resources.getQuantityString(R.plurals.add_game_adding, kept.size, kept.size)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun displayName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    private fun isPlayableFile(name: String?): Boolean {
        val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return extension in playableExtensions
    }

    /** Multi-file picker that opens in the Downloads folder when the device allows it. */
    private class OpenGameFiles : ActivityResultContracts.OpenMultipleDocuments() {
        override fun createIntent(
            context: Context,
            input: Array<String>,
        ): Intent {
            return super.createIntent(context, input).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_URI)
                }
            }
        }
    }

    companion object {
        // Every extension a supported console understands, plus zip archives holding one game.
        private val playableExtensions: Set<String> by lazy {
            GameSystem.all()
                .flatMap { it.supportedExtensions + it.uniqueExtensions }
                .map { it.lowercase() }
                .toSet() + "zip"
        }

        private val DOWNLOADS_URI: Uri =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

        fun pickFiles(context: Context) {
            val intent = Intent(context, GameFilePickerLauncher::class.java)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
