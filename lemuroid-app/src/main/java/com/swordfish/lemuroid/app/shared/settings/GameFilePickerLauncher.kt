package com.swordfish.lemuroid.app.shared.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.swordfish.lemuroid.R
import com.swordfish.lemuroid.app.shared.library.LibraryIndexScheduler
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

    private fun addGames(uris: List<Uri>) {
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
        private val DOWNLOADS_URI: Uri =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

        fun pickFiles(context: Context) {
            val intent = Intent(context, GameFilePickerLauncher::class.java)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
