package com.swordfish.lemuroid.app.shared.online.world

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Finds the [WorldAdapter] for a game, from its own ROM file. */
object WorldAdapters {
    private const val MAX_ROM_SIZE = 33 * 1024 * 1024
    private val ROM_EXTENSIONS = setOf("gba", "gb", "gbc")

    /** The adapter for this ROM, or null when its world can't be shared (yet). */
    fun forRom(
        systemId: String,
        rom: ByteArray,
    ): WorldAdapter? {
        return when (systemId) {
            "gba" -> {
                if (rom.size < 0xC0) return null
                val gameCode = String(rom, 0xAC, 4, Charsets.US_ASCII)
                val version = rom[0xBC].toInt() and 0xFF
                Gen3Adapter.layoutFor(gameCode, version)?.let { Gen3Adapter(Rom(rom, 0x08000000L), it) }
            }
            else -> null
        }
    }

    /** Reads the game's ROM (unzipping it when needed), or null. */
    suspend fun loadRom(
        context: Context,
        game: Game,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(game.fileUri)
                val input: InputStream =
                    if (uri.scheme == "file") {
                        File(uri.path!!).inputStream()
                    } else {
                        context.contentResolver.openInputStream(uri) ?: error("Cannot open ${game.fileName}")
                    }
                input.use { stream ->
                    if (game.fileName.endsWith(".zip", ignoreCase = true)) {
                        ZipInputStream(stream).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                val extension = entry.name.substringAfterLast('.', "").lowercase()
                                if (!entry.isDirectory && extension in ROM_EXTENSIONS) return@runCatching zip.readLimited()
                            }
                            null
                        }
                    } else {
                        stream.readLimited()
                    }
                }
            }.onFailure { Timber.w(it, "Could not read the ROM of ${game.title}") }.getOrNull()
        }

    private fun InputStream.readLimited(): ByteArray? {
        val bytes = readBytes()
        return bytes.takeIf { it.size <= MAX_ROM_SIZE }
    }
}
