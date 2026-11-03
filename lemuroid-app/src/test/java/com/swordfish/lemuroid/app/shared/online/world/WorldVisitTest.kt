package com.swordfish.lemuroid.app.shared.online.world

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [34])
class WorldVisitTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun mon(
        key: String,
        species: Int = 1,
    ) = PartyMon(key, species, byteArrayOf(species.toByte()))

    @Test
    fun `a trade swaps one Pokémon for another at home`() {
        val ledger = TradeLedger()
        val home = listOf(mon("cyndaquil"), mon("pidgey"), mon("rattata"))
        // During the visit we caught a Sentret; then traded Pidgey for Totodile.
        val before = home + mon("sentret")
        ledger.linkEnded(before, listOf(mon("cyndaquil"), mon("rattata"), mon("sentret"), mon("totodile")))
        assertEquals(listOf("cyndaquil", "rattata", "totodile"), ledger.applyTo(home)!!.map { it.key })
    }

    @Test
    fun `a battle changes nothing, and a Pokémon traded on doesn't come home`() {
        val ledger = TradeLedger()
        val home = listOf(mon("a"), mon("b"))
        ledger.linkEnded(home, home)
        assertTrue(ledger.isEmpty)
        ledger.linkEnded(home, listOf(mon("a"), mon("x")))
        ledger.linkEnded(listOf(mon("a"), mon("x")), listOf(mon("a"), mon("y")))
        assertEquals(listOf("a", "y"), ledger.applyTo(home)!!.map { it.key })
        assertEquals(listOf("b"), ledger.sent)
    }

    @Test
    fun `an evolved Pokémon from a trade comes home evolved`() {
        val ledger = TradeLedger()
        ledger.linkEnded(listOf(mon("a"), mon("b")), listOf(mon("a"), mon("kadabra", species = 65)))
        assertEquals(65, ledger.applyTo(listOf(mon("a"), mon("b")))!![1].species)
    }

    @Test
    fun `trades that don't fit the party give up`() {
        val ledger = TradeLedger()
        val home = (1..6).map { mon("home$it") }
        // Traded away something caught during the visit: home stays full, and one more comes.
        ledger.linkEnded(listOf(mon("caught")), listOf(mon("gift")))
        assertNull(ledger.applyTo(home))
    }

    @Test
    fun `the visit is kept on disk`() {
        val store = VisitStore(File(temporary.root, "visits/7"))
        val ledger = TradeLedger()
        ledger.linkEnded(listOf(mon("a"), mon("b")), listOf(mon("a"), mon("c", 158)))
        val record = VisitRecord("BE4YED", "Misty", "24.4", 13, 6, listOf(mon("a"), mon("b")), ledger)
        store.start(record, byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        assertTrue(store.exists())
        val read = store.record()!!
        assertEquals("BE4YED", read.room)
        assertEquals("Misty", read.host)
        assertEquals("24.4", read.homeMap)
        assertEquals(13, read.homeX)
        assertEquals(listOf("b"), read.ledger.sent)
        assertEquals(158, read.ledger.received.single().species)
        assertArrayEquals(byteArrayOf(158.toByte()), read.ledger.received.single().data)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.state())
        store.clear()
        assertTrue(!store.exists())
    }

    // The bytes that took a Gold, a Silver and a Crystal player next to the host and saved their
    // game afterwards in a desktop emulator (a fresh boot from that save was back home with the
    // traded Pokémon).
    @Test
    fun `warp and save scripts for Gold, Silver and Crystal`() {
        val expectations =
            mapOf(
                "GOLD_ROM" to Triple(0xCF7E, 0xC6E8, "0e05234c90"),
                "SILVER_ROM" to Triple(0xCF7E, 0xC6E8, "0e05234c90"),
                "CRYSTAL_ROM" to Triple(0xD086, 0xC7E8, "0e05854b91"),
            )
        var checked = 0
        for ((variable, expected) in expectations) {
            val file = System.getenv(variable)?.let(::File)?.takeIf { it.exists() } ?: continue
            val adapter = WorldAdapters.forRom("gbc", readRom(file))!!
            val ram = GameMemory.SYSTEM_RAM_BASE - 0xC000
            val (textBuffer, scriptBuffer, save) = expected
            val warp = adapter.visitWarp("26.3", 29, 4)!!.writes
            assertEquals(ram + textBuffer, warp[0].first)
            assertEquals("3c1a031d04" + save.takeLast(2), warp[0].second.toHex())
            assertEquals(ram + scriptBuffer, adapter.saveGame()!!.writes[0].first)
            assertEquals(save, adapter.saveGame()!!.writes[0].second.toHex())
            checked++
        }
        assumeTrue(checked > 0)
    }

    @Test
    fun `no visits when the save routine isn't where it should be`() {
        val adapter = Gen2Adapter(Rom(ByteArray(0x200000), 0L), Gen2Adapter.CRYSTAL)
        assertNull(adapter.saveGame())
    }

    // Real Gold work RAM (WORLD_E2E/gold/you.wram): read the party, write it back, read the same.
    @Test
    fun `party of a real Gold game reads and writes back the same`() {
        val folder = System.getenv("WORLD_E2E")?.let { File(it, "gold") }
        val file = System.getenv("GOLD_ROM")?.let(::File)
        assumeTrue(folder != null && File(folder, "you.wram").exists() && file != null && file.exists())
        val adapter = WorldAdapters.forRom("gbc", readRom(file!!))!!
        val wram = File(folder, "you.wram").readBytes()
        val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ByteBuffer.wrap(wram.copyOf()))))
        val party = adapter.party(memory)
        assertNotNull(party)
        val copy = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, ByteBuffer.wrap(ByteArray(wram.size)))))
        assertTrue(adapter.writeParty(party!!)!!.applyTo(copy))
        assertEquals(party.map { it.key }, adapter.party(copy)!!.map { it.key })
        assertEquals(party.map { it.species }, adapter.party(copy)!!.map { it.species })
    }

    // The visit controller itself, with Crystal's layout over a work RAM block that a fake
    // emulator snapshots and restores: join, trade while the link is on, then go home.
    @Test
    fun `join, trade and come home with the app's own visit code`() =
        runBlocking {
            val layout = Gen2Adapter.CRYSTAL
            val visitLayout = layout.visit!!
            // A ROM where the save routine looks right (call, then return).
            val rom = ByteArray(0x200000)
            val save = Gen2Adapter.romOffset(visitLayout.saveBank, visitLayout.saveAddress)
            rom[save] = 0xCD.toByte()
            rom[save + 3] = 0xC9.toByte()
            val adapter = Gen2Adapter(Rom(rom, 0L), layout)
            val wram = ByteBuffer.allocate(0x8000)
            val memory = GameMemory(listOf(GameMemory.Region(GameMemory.SYSTEM_RAM_BASE, wram)))
            fun put(address: Int, vararg bytes: Int) = bytes.forEachIndexed { i, b -> wram.put(address - 0xC000 + i, b.toByte()) }
            fun u8(address: Int) = wram.get(address - 0xC000).toInt() and 0xFF
            fun setParty(vararg dvs: Int) {
                put(visitLayout.partyCount, dvs.size)
                dvs.forEachIndexed { slot, dv ->
                    put(visitLayout.partyCount + 1 + slot, dv)
                    put(visitLayout.partyMons + slot * 48, dv)
                    put(visitLayout.partyMons + slot * 48 + 21, dv, dv)
                }
                put(visitLayout.partyCount + 1 + dvs.size, 0xFF)
            }
            setParty(0x9B, 0x10, 0x13)
            var sram = byteArrayOf(1)
            val emulator =
                object : WorldVisit.Emulator {
                    override suspend fun <T> betweenFrames(block: (WorldVisit.Core) -> T): T =
                        block(
                            object : WorldVisit.Core {
                                override fun saveState() = ByteArray(0x8000).also { wram.position(0); wram.get(it); wram.position(0) }

                                override fun loadState(state: ByteArray): Boolean {
                                    wram.position(0)
                                    wram.put(state)
                                    wram.position(0)
                                    return true
                                }

                                override fun saveSram() = sram.copyOf()

                                override fun loadSram(saved: ByteArray): Boolean {
                                    sram = saved
                                    return true
                                }
                            },
                        )

                    override suspend fun persist() {}
                }
            // The game runs queued scripts right away.
            val game =
                launch(Dispatchers.Default) {
                    while (isActive) {
                        if (u8(layout.inPlace!!.queueFlag) != 0) put(layout.inPlace!!.queueFlag, 0)
                        delay(20)
                    }
                }
            val notices = ArrayList<WorldVisit.Notice>()
            val store = VisitStore(File(temporary.root, "visits/1"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val visit = WorldVisit(adapter, { memory }, emulator, store, scope) { notices += it }
            val look = Look(1, 0, false)

            visit.maybeJoin("R00M", "Misty", "johto-c", Avatar("26.3", 29 * 16, 4 * 16, look), Avatar("24.4", 13 * 16, 6 * 16, look))
            assertEquals("Misty", visit.visiting.value)
            assertTrue(store.exists())
            // The warp to the host's step is queued.
            assertEquals(0x3C, u8(visitLayout.warpScriptBuffer))
            assertEquals(29, u8(visitLayout.warpScriptBuffer + 3))

            // While visiting: the money changes, then a trade (Pidgey for Totodile) on the link.
            put(0xD84E, 0x99)
            put(layout.inPlace!!.linkMode, 1)
            delay(800)
            setParty(0x9B, 0x13, 0x9E)
            put(layout.inPlace!!.linkMode, 0)
            delay(1200)
            sram = byteArrayOf(2)

            visit.goHome()
            game.cancel()
            scope.cancel()
            assertNull(visit.visiting.value)
            assertTrue(!store.exists())
            // Home as it was (money too), with the trade, and the save script queued last.
            assertEquals(0, u8(0xD84E))
            assertArrayEquals(byteArrayOf(1), sram)
            assertEquals(listOf(0x9B, 0x13, 0x9E), adapter.party(memory)!!.map { it.species })
            assertEquals("0e05854b91", ByteArray(5) { wram.get(layout.inPlace!!.scriptBuffer - 0xC000 + it) }.toHex())
            // Totodile (158) is caught in the Pokédex.
            assertTrue(u8(visitLayout.pokedexCaught + 157 / 8) and (1 shl (157 % 8)) != 0)
            assertEquals(listOf(WorldVisit.Notice.Joined("Misty"), WorldVisit.Notice.Home(traded = true)), notices)
        }

    private fun readRom(file: File): ByteArray {
        if (!file.name.endsWith(".zip", ignoreCase = true)) return file.readBytes()
        ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().first { it.name.endsWith(".gbc", ignoreCase = true) }
            return zip.getInputStream(entry).use { it.readBytes() }
        }
    }
}
