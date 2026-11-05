package app.kinclude.net

import app.kinclude.core.Contact
import app.kinclude.core.Household
import app.kinclude.core.LocalSigner
import app.kinclude.core.MemoryStore
import app.kinclude.core.Role
import dev.forgesworn.kithmoot.crypto.Entropy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket

/**
 * The emulator's two-person test, without the emulator: two households on
 * their own relay pools, over real sockets to test/ws-relay.mjs. Mum makes
 * a room and invites Nan, who knows her; Nan accepts; they talk.
 */
class HouseholdsOverSocketsTest {
    private var relay: Process? = null
    private val port = 19000 + (Math.random() * 1000).toInt()
    private val root = File(System.getProperty("kinclude.root") ?: "../..")

    @Before fun startRelay() {
        val script = File(root, "test/ws-relay.mjs")
        assumeTrue("needs npm ci at the repository root", File(root, "node_modules/ws").isDirectory && script.isFile)
        relay = ProcessBuilder("node", script.absolutePath, port.toString()).directory(root).redirectErrorStream(true).start()
        repeat(100) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return
            Thread.sleep(50)
        }
        error("relay did not start")
    }

    @After fun stopRelay() { relay?.destroy() }

    private suspend fun until(what: String, timeoutMs: Long = 30_000, check: suspend () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (check()) return; delay(100) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun mumInvitesNanAndTheyTalk() = runBlocking {
        val url = "ws://127.0.0.1:$port"
        val nan = Household.open(RelayPool(listOf(url)), LocalSigner(Entropy.bytes(32)), MemoryStore(), "Nan")
        nan.start()
        val mum = Household.open(RelayPool(listOf(url)), LocalSigner(Entropy.bytes(32)), MemoryStore(), "Mum")
        mum.start()
        mum.setContacts(listOf(Contact(nan.me, "Nan", kin = true)))
        nan.setContacts(listOf(Contact(mum.me, "Mum", kin = true)))

        val room = mum.createRoom("Home")
        room.propose(nan.me, Role.ADULT, "Nan")
        until("Nan's invitation") { nan.data.invites.isNotEmpty() }
        assertEquals("Home", nan.data.invites.single().envelope.name)
        val nanRoom = nan.acceptInvite(nan.data.invites.single().id)!!
        until("Nan let in") { nanRoom.view().reading }
        room.send("Did you see the heron this morning?")
        until("Mum's message at Nan's") { nanRoom.view().messages.any { it.text == "Did you see the heron this morning?" } }
        nanRoom.send("Yes! It was huge.")
        until("Nan's reply at Mum's") { room.view().messages.any { it.text == "Yes! It was huge." } }
        assertTrue(room.view().state!!.member(nan.me) != null)
        mum.close(); nan.close()
    }
}
