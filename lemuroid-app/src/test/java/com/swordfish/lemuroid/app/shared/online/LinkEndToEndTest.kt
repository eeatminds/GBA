package com.swordfish.lemuroid.app.shared.online

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.net.Socket

/**
 * Two phones trading in place, through everything real but the phones: the room server (run
 * locally), a network that adds delay each way, the app's own room sessions and Game Boy link
 * bridges, and two real Game Boy emulators (desktop builds of our Gambatte) running Gold and
 * Silver, driven like players by a script that reads their screens.
 *
 * One invites, the other accepts; each starts the game's link right where it stands when the
 * app would (on accepting / on hearing the answer), then they trade and leave.
 *
 * Off unless LINK_E2E names the emulator driver folder (with link_session.py and
 * tcp_delay_proxy.py); also needs LINK_E2E_CORES (gambatte_libretro.so), LINK_E2E_STATES
 * (gold.state and silver.state), GOLD_ROM, SILVER_ROM and LINK_E2E_OUT. LINK_E2E_DELAY_MS sets
 * the delay each way (default 80), LINK_E2E_KIND trade or battle, LINK_E2E_ASKER_DELAY_MS how much
 * later the asker's game starts linking, LINK_E2E_GAME crystal for two Crystal games (CRYSTAL_ROM, as
 * a .gbc, with crystal-cr_c.state and crystal-cr_a.state).
 *
 * LINK_E2E_OUTAGE ("words|seconds|server or client") cuts that phone's connection for that long
 * once its log says those words (the room keeps its seat; the games must carry on after).
 * LINK_E2E_LEAVE ("words|server or client[|ms]") makes that player leave the room for good then
 * (or that many ms later):
 * the other game must not wait forever (a battle ends, as if they ran).
 * LINK_E2E_RETRY (server or client): that game asks again if its first try doesn't link up
 * (with a long LINK_E2E_ASKER_DELAY_MS, the first try is alone and fails).
 */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class LinkEndToEndTest {
    private val processes = ArrayList<Process>()
    private val drivers = java.util.Collections.synchronizedList(ArrayList<Process>())
    private val driverOf = java.util.concurrent.ConcurrentHashMap<String, Process>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        processes.forEach { it.destroyForcibly() }
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun waitForPort(port: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(100)
        }
        error("Nothing listens on $port")
    }

    private fun start(
        command: List<String>,
        directory: File,
        log: File,
        environment: Map<String, String> = emptyMap(),
    ): Process =
        ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { environment().putAll(environment) }
            .start()
            .also { processes += it }

    @Test
    fun `invite, accept, then trade right where both stand`() =
        runBlocking {
            val rig = env("LINK_E2E")?.let(::File)
            assumeTrue(rig != null && File(rig, "link_session.py").exists())
            val cores = File(env("LINK_E2E_CORES")!!)
            val states = File(env("LINK_E2E_STATES")!!)
            val out = File(env("LINK_E2E_OUT")!!).apply { mkdirs() }
            val delay = env("LINK_E2E_DELAY_MS") ?: "80"
            val kind = env("LINK_E2E_KIND") ?: "trade"
            out.listFiles()?.forEach { it.delete() }

            // The room server, and a slow network in front of it for each phone.
            val serverPort = freePort()
            val serverDir = File(System.getProperty("user.dir")).let { if (File(it, "server").exists()) File(it, "server") else File(it.parentFile, "server") }
            start(listOf("node", "src/server.js"), serverDir, File(out, "room-server.log"), mapOf("PORT" to "$serverPort", "HOST" to "127.0.0.1"))
            waitForPort(serverPort)
            val proxies =
                List(2) {
                    freePort().also { port ->
                        start(
                            listOf("python3", "-I", "tcp_delay_proxy.py", "$port", "$serverPort", delay, File(out, "outage-$it").path),
                            rig!!,
                            File(out, "proxy$it.log"),
                        )
                        waitForPort(port)
                    }
                }

            // Each phone: the app's room session, and an emulator plugged into its link bridge.
            fun phone(
                index: Int,
                host: Boolean,
                code: String?,
            ): OnlineSession {
                val role = if (host) "server" else "client"
                val crystal = env("LINK_E2E_GAME") == "crystal"
                val rom = if (crystal) env("CRYSTAL_ROM")!! else env(if (host) "GOLD_ROM" else "SILVER_ROM")!!
                val state = File(states, if (crystal) (if (host) "crystal-cr_c.state" else "crystal-cr_a.state") else (if (host) "gold.state" else "silver.state")).path
                val config =
                    OnlineConfig(
                        serverUrl = "http://127.0.0.1:${proxies[index]}",
                        playerName = if (host) "Gold" else "Silver",
                        isHost = host,
                        roomCode = code,
                        kind = RoomKind.LINK,
                        mode = LinkMode.GAME_BOY,
                        isPublic = false,
                        gameTitle = "Pokemon Gold",
                        systemId = "gbc",
                        coreName = "gambatte",
                        inputDelayFrames = 0,
                        maxPlayers = 8,
                        sameWorld = true,
                    )
                return OnlineSession(config, scope) { variables ->
                    val port = variables.toMap()["gambatte_gb_link_network_port"] ?: return@OnlineSession
                    val work = File(out, "work-$role").apply { mkdirs() }
                    val driver = start(
                        listOf(
                            "python3", "-I", "link_session.py", rig!!.path, cores.path, work.path, rom, state, role, kind,
                            File(out, "shot").path, env("LINK_E2E_SECONDS") ?: "150", "2", if (crystal) "crystal" else "gold",
                        ),
                        rig,
                        File(out, "$role.log"),
                        mapOf(
                            "LINK_PORT" to port,
                            "BRIDGE" to "1",
                            "PLAN" to kind,
                            "START_FILE" to File(out, "start-$role").path,
                            "GAMBATTE_LINK_TRACE" to "1",
                            "WEAK" to if (kind == "battle" && host) "1" else "0",
                            "RETRY" to if (env("LINK_E2E_RETRY") == role) "1" else "0",
                            "NO_CONFIRM" to if (env("LINK_E2E_NO_CONFIRM") == role) "1" else "0",
                            // LINK_E2E_SECOND_YES=client|battle|2.7: that phone first starts a battle, then a
                            // trade over it 2.7 s later (two yeses in a row).
                            "FIRST_KIND" to (env("LINK_E2E_SECOND_YES")?.split('|')?.takeIf { it[0] == role }?.get(1) ?: ""),
                            "RESTART_AFTER" to (env("LINK_E2E_SECOND_YES")?.split('|')?.takeIf { it[0] == role }?.get(2) ?: ""),
                        ),
                    )
                    driverOf[role] = driver
                    drivers += driver
                }
            }

            val gold = phone(0, host = true, code = null)
            gold.start()
            val room = withTimeout(20_000) { gold.state.first { it is OnlineSession.State.InRoom } as OnlineSession.State.InRoom }
            val silver = phone(1, host = false, code = room.code)
            silver.start()
            val joined = withTimeout(20_000) { silver.state.first { it is OnlineSession.State.InRoom } as OnlineSession.State.InRoom }
            withTimeout(20_000) { gold.state.first { (it as? OnlineSession.State.InRoom)?.players?.size == 2 } }

            // As the app does: the friend says yes and starts at once; the asker starts on hearing it.
            scope.launch {
                silver.events.collect { event ->
                    if (event is OnlineSession.Event.Invited) {
                        silver.answerInvite(event.from, true)
                        File(out, "start-client").createNewFile()
                    }
                }
            }
            scope.launch {
                gold.events.collect { event ->
                    if (event is OnlineSession.Event.InviteAnswered && event.accepted) {
                        // The asker's game may be busy for a while (a menu, a text box) before it starts.
                        Thread.sleep(env("LINK_E2E_ASKER_DELAY_MS")?.toLong() ?: 0)
                        File(out, "start-server").createNewFile()
                    }
                }
            }
            // A phone loses its connection for a while, or a player leaves, when its log says so.
            fun whenLogSays(
                role: String,
                words: String,
                action: () -> Unit,
            ) = Thread {
                val log = File(out, "$role.log")
                while (!(log.exists() && words in log.readText())) Thread.sleep(200)
                action()
            }.apply {
                isDaemon = true
                start()
            }
            env("LINK_E2E_OUTAGE")?.split('|')?.let { (words, seconds, role) ->
                whenLogSays(role, words) {
                    val outage = File(out, "outage-${if (role == "server") 0 else 1}")
                    println("---- $role's connection is down for $seconds s")
                    outage.createNewFile()
                    Thread.sleep(seconds.toLong() * 1000)
                    outage.delete()
                    println("---- $role's connection is back")
                }
            }
            val leaveAfter = env("LINK_E2E_LEAVE")?.split('|')?.getOrNull(2)?.toLong() ?: 0
            val leaver = env("LINK_E2E_LEAVE")?.split('|')?.let { (words, role) -> role to words }
            leaver?.let { (role, words) ->
                whenLogSays(role, words) {
                    Thread.sleep(leaveAfter)
                    println("---- $role leaves the room")
                    (if (role == "server") gold else silver).close()
                    driverOf[role]?.destroy()
                    File(out, "left-$role").createNewFile()
                }
            }

            // Give the emulators time to boot, then ask.
            Thread.sleep(8_000)
            gold.invite(joined.clientId, kind)

            // Both drivers end on their own (after leaving the trade, or the battle).
            while (drivers.size < 2) Thread.sleep(100)
            drivers.toList().forEach { it.waitFor() }
            val server = File(out, "server.log").readText()
            val client = File(out, "client.log").readText()
            println("---- server\n" + server.lines().filter { "screen" !in it }.joinToString("\n"))
            println("---- client\n" + client.lines().filter { "screen" !in it }.joinToString("\n"))
            (0..1).forEach { println("---- proxy $it\n" + File(out, "proxy$it.log").readText()) }
            // What a link report would hold (both phones' app side, in one log here).
            println("---- link log\n" + LinkLog.lines().joinToString("\n"))
            // A player who left has no say; the one who stayed must finish.
            val stayed = listOf("server" to server, "client" to client).filter { it.first != leaver?.first }
            stayed.forEach { (role, log) ->
                if (kind == "trade") assertTrue("$role traded", "trade done" in log) else assertTrue("$role battled", "battle over" in log)
            }
            gold.close()
            silver.close()
        }
}
