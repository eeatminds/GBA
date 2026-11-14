package com.swordfish.lemuroid.app.shared.online.world

import android.content.Context
import android.net.Uri
import com.swordfish.lemuroid.lib.library.db.entity.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Finds the [WorldAdapter] for a game, from its own ROM file. */
object WorldAdapters {
    private const val MAX_ROM_SIZE = 33 * 1024 * 1024
    private val ROM_EXTENSIONS = setOf("gba", "gb", "gbc")
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

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
                val image = Rom(rom, 0x08000000L)
                Gen3Adapter.layoutFor(gameCode, version)?.let { Gen3Adapter(image, Gen3Adapter.followGameCode(image, it, gameCode, version)) }
            }
            "gb", "gbc" -> {
                if (rom.size < 0x150) return null
                val title = String(rom, 0x134, 11, Charsets.US_ASCII)
                val gameCode = String(rom, 0x13F, 4, Charsets.US_ASCII)
                Gen2Adapter.layoutFor(title, gameCode)?.let { Gen2Adapter(Rom(rom, 0L), it) }
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
                BufferedInputStream(input).use { stream -> readRom(stream, game.fileName) }
            }.onFailure { Timber.w(it, "Could not read the ROM of ${game.title}") }.getOrNull()
        }

    /**
     * The ROM in [stream]: the file itself, or the game inside it when it is a zip. A zipped game
     * keeps the name of the file inside ([fileName]), so zips are told apart by their first bytes.
     */
    fun readRom(
        stream: InputStream,
        fileName: String,
    ): ByteArray? {
        val input = if (stream.markSupported()) stream else BufferedInputStream(stream)
        input.mark(ZIP_MAGIC.size)
        val start = ByteArray(ZIP_MAGIC.size)
        var read = 0
        while (read < start.size) {
            val count = input.read(start, read, start.size - read)
            if (count < 0) break
            read += count
        }
        input.reset()
        if (read < ZIP_MAGIC.size || !start.contentEquals(ZIP_MAGIC)) return input.readLimited()
        ZipInputStream(input).use { zip ->
            var fallback: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val extension = name.substringAfterLast('.', "").lowercase()
                when {
                    name == fileName -> return zip.readLimited()
                    extension in ROM_EXTENSIONS && fallback == null -> fallback = zip.readLimited()
                }
            }
            return fallback
        }
    }

    private fun InputStream.readLimited(): ByteArray? {
        val bytes = readBytes()
        return bytes.takeIf { it.size <= MAX_ROM_SIZE }
    }
}
