package com.swordfish.lemuroid.app.shared.online.world

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * What the trades during a visit changed: the Pokémon that left our party ([sent], by key) and
 * the ones that joined it ([received]). A Pokémon received and then traded on is in neither.
 */
class TradeLedger(
    val sent: MutableList<String> = ArrayList(),
    val received: MutableList<PartyMon> = ArrayList(),
) {
    val isEmpty: Boolean get() = sent.isEmpty() && received.isEmpty()

    /** A link session (a trade, or a battle that changes nothing) went from [before] to [after]. */
    fun linkEnded(
        before: List<PartyMon>,
        after: List<PartyMon>,
    ) {
        val stayed = after.map { it.key }.toMutableList()
        val gone = before.filterNot { stayed.remove(it.key) }
        val earlier = before.map { it.key }.toMutableList()
        val came = after.filterNot { earlier.remove(it.key) }
        for (mon in gone) {
            val index = received.indexOfFirst { it.key == mon.key }
            if (index >= 0) received.removeAt(index) else sent += mon.key
        }
        received += came
    }

    /** [home] (our party before the visit) with the trades applied, or null when it won't fit. */
    fun applyTo(home: List<PartyMon>): List<PartyMon>? {
        val leaving = sent.toMutableList()
        val merged = home.filterNot { leaving.remove(it.key) } + received
        return merged.takeIf { it.size <= PARTY_SIZE }
    }

    companion object {
        const val PARTY_SIZE = 6
    }
}

/**
 * A visit to the host's world, kept on disk so that coming home works even after the app was
 * closed: our game as it was when we joined (emulator snapshot and in-game save), where we
 * stood, our party then, and the trades since.
 */
class VisitRecord(
    val room: String,
    val host: String,
    val homeMap: String,
    val homeX: Int,
    val homeY: Int,
    val homeParty: List<PartyMon>,
    val ledger: TradeLedger,
)

class VisitStore(private val folder: File) {
    private val stateFile get() = File(folder, "home.state")
    private val sramFile get() = File(folder, "home.srm")
    private val recordFile get() = File(folder, "visit.json")

    fun exists(): Boolean = recordFile.exists() && stateFile.exists() && sramFile.exists()

    fun start(
        record: VisitRecord,
        state: ByteArray,
        sram: ByteArray,
    ) {
        folder.mkdirs()
        stateFile.writeBytes(state)
        sramFile.writeBytes(sram)
        update(record)
    }

    fun update(record: VisitRecord) {
        folder.mkdirs()
        // Write next to it, then swap, so a half-written file never replaces a good one.
        val next = File(folder, "visit.json.new")
        next.writeText(toJson(record).toString())
        next.renameTo(recordFile)
    }

    fun record(): VisitRecord? = runCatching { fromJson(JSONObject(recordFile.readText())) }.getOrNull()

    fun state(): ByteArray? = runCatching { stateFile.readBytes() }.getOrNull()

    fun sram(): ByteArray? = runCatching { sramFile.readBytes() }.getOrNull()

    fun clear() {
        folder.deleteRecursively()
    }

    private fun monJson(mon: PartyMon) = JSONObject().put("key", mon.key).put("species", mon.species).put("data", mon.data.toHex())

    private fun monFrom(json: JSONObject) = PartyMon(json.getString("key"), json.getInt("species"), json.getString("data").hexToBytes())

    private fun toJson(record: VisitRecord) =
        JSONObject()
            .put("room", record.room)
            .put("host", record.host)
            .put("homeMap", record.homeMap)
            .put("homeX", record.homeX)
            .put("homeY", record.homeY)
            .put("homeParty", JSONArray(record.homeParty.map(::monJson)))
            .put("sent", JSONArray(record.ledger.sent))
            .put("received", JSONArray(record.ledger.received.map(::monJson)))

    private fun fromJson(json: JSONObject): VisitRecord {
        fun mons(name: String) = json.getJSONArray(name).let { array -> (0 until array.length()).map { monFrom(array.getJSONObject(it)) } }
        val sent = json.getJSONArray("sent").let { array -> (0 until array.length()).map { array.getString(it) } }
        return VisitRecord(
            room = json.getString("room"),
            host = json.getString("host"),
            homeMap = json.getString("homeMap"),
            homeX = json.getInt("homeX"),
            homeY = json.getInt("homeY"),
            homeParty = mons("homeParty"),
            ledger = TradeLedger(sent.toMutableList(), mons("received").toMutableList()),
        )
    }
}

/**
 * Joining the host's world: in a Same World room, a guest whose game shares the host's world is
 * taken to where the host stands, and walks around there with everyone. Coming home (closing
 * the game, or the room ending) puts the game back exactly as it was before joining, keeping
 * only the Pokémon traded meanwhile, and the game saves itself so the trades stay.
 */
class WorldVisit(
    private val adapter: WorldAdapter,
    private val memory: () -> GameMemory?,
    private val emulator: Emulator,
    private val store: VisitStore,
    private val scope: CoroutineScope,
    private val notify: (Notice) -> Unit,
) {
    /** The running game, as the visit needs it. */
    interface Emulator {
        /** Runs [block] on the emulation thread between two frames and returns its result. */
        suspend fun <T> betweenFrames(block: (Core) -> T): T

        /** Writes the in-game save (and the auto-resume snapshot) to disk now. */
        suspend fun persist()
    }

    /** Only call these inside [Emulator.betweenFrames]. */
    interface Core {
        fun saveState(): ByteArray

        fun loadState(state: ByteArray): Boolean

        fun saveSram(): ByteArray

        fun loadSram(sram: ByteArray): Boolean
    }

    sealed interface Notice {
        data class Joined(val host: String) : Notice

        data class OtherGame(val host: String, val game: String?) : Notice

        /** Back home; [traded]: whether trades came along (and the game saved). */
        data class Home(val traded: Boolean) : Notice

        /** Back where we were, keeping everything (the trades didn't fit the party). */
        data object HomeKeptAll : Notice
    }

    /** The host's name while we visit their world, or null. */
    val visiting: StateFlow<String?> get() = mutableVisiting.asStateFlow()
    private val mutableVisiting = MutableStateFlow<String?>(null)

    private val lock = Mutex()
    private var record: VisitRecord? = null
    private var watch: Job? = null
    private var toldOtherGame = false

    // A link session in progress: our party when it began.
    private var linkBefore: List<PartyMon>? = null

    /** Whether this game can visit at all. */
    val supported: Boolean get() = adapter.visitWarp("1.1", 0, 0) != null && adapter.saveGame() != null

    /**
     * After the game starts: a visit left over from before (the app was closed) either goes on,
     * when we are back in the same room as a guest, or comes home.
     */
    suspend fun resume(room: String?) {
        val saved = withContext(Dispatchers.IO) { store.record() } ?: return
        if (room != null && saved.room == room) {
            lock.withLock { record = saved }
            mutableVisiting.value = saved.host
            startWatching()
        } else {
            goHome()
        }
    }

    /**
     * Called as the world view changes: joins [host] (their character [hostAvatar]) when we
     * aren't visiting yet and our character is free to move.
     */
    suspend fun maybeJoin(
        room: String,
        host: String,
        hostWorld: String?,
        hostAvatar: Avatar?,
        local: Avatar?,
    ) {
        if (record != null || !supported) return
        if (hostWorld != null && hostWorld != adapter.world && hostWorld != Worlds.NONE) {
            if (!toldOtherGame) notify(Notice.OtherGame(host, Worlds.gameName(hostWorld)))
            toldOtherGame = true
            return
        }
        if (hostAvatar == null || local == null || local.covered) return
        // Only between steps, so where we stand is a whole step.
        if (local.x % 16 != 0 || local.y % 16 != 0) return
        val warp = adapter.visitWarp(hostAvatar.map, (hostAvatar.x + 8) / 16, (hostAvatar.y + 8) / 16) ?: return
        lock.withLock {
            if (record != null) return
            val memory = memory() ?: return
            val home =
                emulator.betweenFrames { core ->
                    val party = adapter.party(memory) ?: return@betweenFrames null
                    val state = core.saveState()
                    val sram = core.saveSram()
                    if (!warp.applyTo(memory)) return@betweenFrames null
                    Triple(state, sram, party)
                } ?: return
            val (state, sram, party) = home
            val visit = VisitRecord(room, host, local.map, local.x / 16, local.y / 16, party, TradeLedger())
            withContext(Dispatchers.IO) { store.start(visit, state, sram) }
            record = visit
        }
        mutableVisiting.value = host
        notify(Notice.Joined(host))
        startWatching()
    }

    // Trades happen while the game's link is on: compare the party before and after.
    private fun startWatching() {
        if (watch != null) return
        watch =
            scope.launch(Dispatchers.Default) {
                while (isActive) {
                    delay(WATCH_MS)
                    val memory = memory() ?: continue
                    val active = runCatching { adapter.linkActive(memory) }.getOrDefault(false)
                    val before = linkBefore
                    when {
                        active && before == null -> linkBefore = adapter.party(memory)
                        !active && before != null -> {
                            linkBefore = null
                            // Give the game a moment to finish putting the party back.
                            delay(WATCH_MS)
                            val after = adapter.party(memory) ?: continue
                            lock.withLock {
                                val visit = record ?: return@withLock
                                visit.ledger.linkEnded(before, after)
                                withContext(Dispatchers.IO) { store.update(visit) }
                            }
                        }
                    }
                }
            }
    }

    /** Takes us home (see the class comment). Does nothing when we aren't visiting. */
    suspend fun goHome() {
        lock.withLock {
            val visit = record ?: withContext(Dispatchers.IO) { store.record() } ?: return
            watch?.cancel()
            watch = null
            val state = withContext(Dispatchers.IO) { store.state() }
            val sram = withContext(Dispatchers.IO) { store.sram() }
            val memory = memory()
            val merged = visit.ledger.applyTo(visit.homeParty)
            if (state == null || sram == null || memory == null) {
                Timber.w("Visit: home snapshot missing, staying as we are")
            } else if (merged == null) {
                // More Pokémon than fit the party: keep everything and just walk back home.
                adapter.visitWarp(visit.homeMap, visit.homeX, visit.homeY)?.let { warp ->
                    emulator.betweenFrames { warp.applyTo(memory) }
                }
                notify(Notice.HomeKeptAll)
            } else {
                val traded = !visit.ledger.isEmpty
                emulator.betweenFrames { core ->
                    core.loadState(state)
                    core.loadSram(sram)
                    if (traded) {
                        adapter.writeParty(merged)?.applyTo(memory)
                        adapter.registerInPokedex(memory, visit.ledger.received.map { it.species })?.applyTo(memory)
                        adapter.saveGame()?.applyTo(memory)
                    }
                }
                if (traded) waitForScript(memory)
                emulator.persist()
                notify(Notice.Home(traded))
            }
            withContext(Dispatchers.IO) { store.clear() }
            record = null
            linkBefore = null
            mutableVisiting.value = null
        }
    }

    private suspend fun waitForScript(memory: GameMemory) {
        val deadline = SystemClock.uptimeMillis() + SAVE_TIMEOUT_MS
        delay(WATCH_MS)
        while (SystemClock.uptimeMillis() < deadline && runCatching { adapter.scriptPending(memory) }.getOrDefault(false)) {
            delay(WATCH_MS)
        }
        // The save itself takes a few frames after the script started.
        delay(WATCH_MS * 2)
    }

    fun stop() {
        watch?.cancel()
        watch = null
    }

    companion object {
        private const val WATCH_MS = 250L
        private const val SAVE_TIMEOUT_MS = 6_000L
    }
}
