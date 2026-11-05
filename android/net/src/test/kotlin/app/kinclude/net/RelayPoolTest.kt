package app.kinclude.net

import app.kinclude.core.KincludeRoom
import app.kinclude.core.LocalSigner
import app.kinclude.core.RoomOptions
import app.kinclude.core.RoomSecrets
import dev.forgesworn.kithmoot.crypto.Entropy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.Socket

/**
 * The Kotlin room engine over real WebSockets, against the same offline
 * relay the web client's tests use (`test/ws-relay.mjs`). Skipped where
 * Node or the repository's npm packages are not installed.
 */
class RelayPoolTest {
    private var relay: Process? = null
    private val port = 18000 + (Math.random() * 1000).toInt()
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

    @Test fun aRoomOverRealSocketsReadsItsOwnMessageBack() = runBlocking {
        val sk = Entropy.bytes(32)
        val pool = RelayPool(listOf("ws://127.0.0.1:$port"))
        val room = KincludeRoom.create(RoomOptions(pool, LocalSigner(sk), "Mum", RoomSecrets("", ""), Entropy.bytes(32)), "Home")
        room.send("over the wire")
        val other = RelayPool(listOf("ws://127.0.0.1:$port"))
        val again = KincludeRoom(RoomOptions(other, LocalSigner(sk), "Mum", room.secrets(), Entropy.bytes(32)))
        again.start()
        repeat(100) { if (again.view().messages.isNotEmpty()) return@repeat; delay(50) }
        assertEquals("Home", again.view().name)
        assertEquals(listOf("over the wire"), again.view().messages.map { it.text })
        room.close(); again.close(); pool.close(); other.close()
    }
}
