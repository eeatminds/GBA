package com.swordfish.lemuroid.app.shared.online

import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * What this phone's Game Boy link did lately (the room, the link bridge, the game's state),
 * sent to the room server when a trade or battle doesn't get going, so the reason can be read
 * there without the phone. Holds no game data: event names, numbers and player names only.
 */
object LinkLog {
    private const val MAX_LINES = 800
    private const val MAX_CORE_LINES = 400
    private val lines = ArrayDeque<String>()
    private val started = System.nanoTime()

    fun add(text: String) {
        val line = String.format(Locale.ROOT, "%8.1f %s", (System.nanoTime() - started) / 1e9, text)
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        Timber.i(text)
    }

    fun lines(): List<String> = synchronized(lines) { lines.toList() }

    /**
     * The emulator core's own lines about the link, from this app's system log (an app can
     * read its own). Empty when the system doesn't allow it.
     */
    fun coreLines(): List<String> =
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "4000").redirectErrorStream(true).start()
            val wanted =
                BufferedReader(InputStreamReader(process.inputStream)).useLines { all ->
                    all.filter { line ->
                        "Libretro Core" in line || ("libretrodroid" in line && "variable" !in line.lowercase())
                    }.toList()
                }
            process.destroy()
            wanted.takeLast(MAX_CORE_LINES)
        }.getOrDefault(emptyList())
}
