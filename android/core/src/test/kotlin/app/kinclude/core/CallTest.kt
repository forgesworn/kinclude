package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** `test/call.test.ts`, in Kotlin, over a stand-in for WebRTC. */
class CallTest {
    private val registry = ConcurrentHashMap<String, FakePc>()
    private val ids = AtomicInteger()

    private inner class FakeLocal(val owner: String) : LocalMedia {
        override fun setMuted(muted: Boolean) {}
        override fun setCameraOff(off: Boolean) {}
        override fun stop() {}
    }

    private inner class FakePc(val local: FakeLocal) : PeerConnectionPort {
        val id = "pc${ids.incrementAndGet()}"
        var other: FakePc? = null
        override var hasRemote = false
        override var state = "new"
        override var onIceCandidate: ((String) -> Unit)? = null
        override var onRemoteMedia: ((Any) -> Unit)? = null
        override var onStateChange: (() -> Unit)? = null
        init { registry[id] = this }
        override suspend fun createOffer() = "offer:$id"
        override suspend fun createAnswer() = "answer:$id"
        override suspend fun setLocal(type: String, sdp: String) { onIceCandidate?.invoke("{\"candidate\":\"cand:$id\"}") }
        override suspend fun setRemote(type: String, sdp: String) {
            hasRemote = true
            other = registry.getValue(sdp.substringAfter(':'))
            if (type == "answer") { link(); other!!.link() }
        }
        override suspend fun addIceCandidate(json: String) {}
        fun link() {
            if (state == "connected") return
            state = "connected"
            onRemoteMedia?.invoke(other!!.local.owner)
            onStateChange?.invoke()
        }
        override fun close() { state = "closed" }
    }

    private inner class FakeEnv(val owner: String) : CallEnvironment {
        val asked = ArrayList<Boolean>()
        override suspend fun startLocal(video: Boolean): LocalMedia { asked += video; return FakeLocal(owner) }
        override fun createPeerConnection(iceServers: List<IceServer>, local: LocalMedia): PeerConnectionPort = FakePc(local as FakeLocal)
    }

    private suspend fun settle(h: TestHousehold) { repeat(3) { quiet(h.relay, h.rooms); delay(30) } }
    private suspend fun connectedTo(call: RoomCall) = call.peers().filter { it.connection == "connected" }.map { it.media as String }.sorted()

    @Test fun twoPeopleCallAndTheJoinGoesFirst() = runBlocking {
        val h = household(Tier.FIRST_MESSAGES)
        val samCall = RoomCall(h.samRoom, FakeEnv("Sam"))
        val dadCall = RoomCall(h.dadRoom, FakeEnv("Dad"))
        val before = h.relay.log.size
        samCall.join(withVideo = true)
        settle(h)
        val first = h.relay.log.drop(before).first { it.pubkey == h.samRoom.device || it.kind == SIGNAL_WRAP_KIND || it.kind == PRESENCE_KIND }
        assertEquals("the durable join record goes out first", KIND_CHAT, first.kind)
        assertEquals(listOf(h.sam.pk), h.dadRoom.view().call!!.on.map { it.participant })

        dadCall.join(withVideo = true)
        settle(h)
        assertEquals(samCall.id, dadCall.id)
        assertEquals(listOf("Dad"), connectedTo(samCall))
        assertEquals(listOf("Sam"), connectedTo(dadCall))
        dadCall.leave()
        settle(h)
        assertEquals(listOf(true, true, false), h.samRoom.view().callLog.map { it.join })
        samCall.close(); dadCall.close(); h.close()
    }

    @Test fun nearlyGrownGuardianCannotJoin() = runBlocking {
        val h = household(Tier.NEARLY_GROWN)
        val samCall = RoomCall(h.samRoom, FakeEnv("Sam"))
        val mumEnv = FakeEnv("Mum")
        val mumCall = RoomCall(h.mumRoom, mumEnv)
        samCall.join(withVideo = false)
        settle(h)
        try { mumCall.join(withVideo = false); fail("joined without the key") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("cannot read this room")) }
        assertTrue("the camera and microphone were never asked for", mumEnv.asked.isEmpty())
        assertTrue(samCall.onCall().isEmpty())
        samCall.close(); h.close()
    }

    @Test fun firstMessagesGuardianJoinsInSightAndStepsOffAtNearlyGrown() = runBlocking {
        val h = household(Tier.FIRST_MESSAGES)
        val samCall = RoomCall(h.samRoom, FakeEnv("Sam"))
        val mumCall = RoomCall(h.mumRoom, FakeEnv("Mum"))
        samCall.join(withVideo = true)
        settle(h)
        mumCall.join(withVideo = false)
        settle(h)
        assertEquals(listOf(h.mum.pk), samCall.onCall().map { it.participant })
        assertEquals(listOf("Mum"), connectedTo(samCall))
        moveTier(h, Tier.NEARLY_GROWN, 1, h.genesis)
        settle(h)
        assertEquals(RoomCall.Status.IDLE, mumCall.status)
        assertTrue(mumCall.ended!!.contains("can no longer read this room"))
        assertTrue(samCall.peers().isEmpty())
        samCall.close(); h.close()
    }

    @Test fun twoStartersEndUpOnOneCall() = runBlocking {
        val h = household(Tier.FIRST_MESSAGES)
        val samCall = RoomCall(h.samRoom, FakeEnv("Sam"))
        val dadCall = RoomCall(h.dadRoom, FakeEnv("Dad"))
        coroutineScope {
            launch { samCall.join(withVideo = false) }
            launch { dadCall.join(withVideo = false) }
        }
        settle(h); settle(h)
        assertEquals(samCall.id, dadCall.id)
        assertEquals(listOf("Dad"), connectedTo(samCall))
        samCall.close(); dadCall.close(); h.close()
    }

    @Test fun signalsOpenOnlyForTheirRecipientWhileFresh() = runBlocking {
        val h = household(Tier.FIRST_MESSAGES)
        val sam = h.samRoom.callContext()
        val dad = h.dadRoom.callContext()
        val call = "ab".repeat(16)
        val wrap = encodeSignal(sam.deviceSk, dad.device, SignalBody("offer", sam.roomId, call, "v=0"), now())
        assertEquals(listOf("p", "expiration"), wrap.tags.map { it[0] })
        assertEquals(sam.device, decodeSignal(wrap, dad.deviceSk, dad.roomId, now())!!.from)
        assertNull(decodeSignal(wrap, sam.deviceSk, sam.roomId, now()))
        assertNull(decodeSignal(wrap, dad.deviceSk, "cd".repeat(32), now()))
        assertNull(decodeSignal(wrap, dad.deviceSk, dad.roomId, now() + 21))
        val forged = encodePresence(Presence(call, true, h.dad.pk, sam.device, sam.credential, false, now()), sam.deviceSk, sam.epoch!!.second)
        assertNull(decodePresence(forged, sam.roomId, dad.epoch!!.second, now()))
        h.close()
    }

    @Test fun presenceKeysMatchTheTypeScript() {
        val key = deriveEpoch(RoomEpoch(1, Vectors.str("keys", "epoch1Secret").hexToBytes())).key
        val (tag, k) = presenceKeys(key)
        assertEquals(Vectors.str("derived", "presenceTag"), tag)
        assertEquals(Vectors.str("derived", "presenceKey"), k.toHex())
    }
}
