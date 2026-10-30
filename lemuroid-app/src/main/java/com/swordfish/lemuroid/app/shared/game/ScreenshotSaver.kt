package com.swordfish.lemuroid.app.shared.game

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves game screenshots where the phone's Gallery can see them (Pictures/<app name>). */
object ScreenshotSaver {
    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        gameTitle: String,
        albumName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeTitle = gameTitle.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifBlank { "game" }.take(60)
            val fileName = "${safeTitle}_$time.png"
            val safeAlbum = albumName.replace(Regex("[^A-Za-z0-9 ._-]"), "").ifBlank { "Screenshots" }

            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values =
                        ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$safeAlbum")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    // Older Android: the app's own Pictures folder needs no permission.
                    val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), safeAlbum)
                    folder.mkdirs()
                    val file = File(folder, fileName)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                }
            }.isSuccess
        }
}
