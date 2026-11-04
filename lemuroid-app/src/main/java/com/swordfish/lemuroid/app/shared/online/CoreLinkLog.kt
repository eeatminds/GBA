package com.swordfish.lemuroid.app.shared.online

/**
 * What the Game Boy core logged about its link cable. It logs to this app's own log, which an
 * app may read: whether its cable reached our link bridge, and whether its network friendly
 * link (the Pokémon fixes) switched on for this game. For telling why a link didn't start.
 */
object CoreLinkLog {
    /** A short summary of the core's latest cable state, or null when it can't be read. */
    fun read(): String? =
        runCatching {
            val process = ProcessBuilder("logcat", "-d", "-t", "4000", "-v", "raw").redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            process.waitFor()
            summarize(lines)
        }.getOrNull()

    /** The summary for the core's log [lines], oldest first. */
    fun summarize(lines: List<String>): String {
        // The core logs its cable from scratch whenever its link options are applied.
        val start = lines.indexOfLast { it.startsWith("Starting GameLink network") }
        if (start < 0) return "cable never started"
        val since = lines.drop(start)
        val fixes = if (since.any { it.startsWith("Link: whole-block exchanges") }) "fixes on" else "fixes off"
        val cable =
            since.lastOrNull { it.startsWith("GameLink network client connected") || it.startsWith("Error") || it.startsWith("Stopping GameLink") }
        val state =
            when {
                cable == null -> "cable not connected"
                cable.startsWith("GameLink network client connected") -> "cable connected"
                cable.startsWith("Stopping") -> "cable stopped"
                else -> "cable: ${cable.trim()}"
            }
        return "$fixes · $state"
    }
}
