package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs

/**
 * Voice and video calls: the Kotlin twin of `src/call-wire.ts` and
 * `src/call.ts` (protocol §8). WebRTC itself sits behind [CallEnvironment],
 * which the Android app implements over the WebRTC library; the rules are
 * here, where they are tested.
 */
const val SIGNAL_KIND = 20462
const val SIGNAL_WRAP_KIND = 21059
const val SIGNAL_EXPIRY_SECONDS = 60L
const val SIGNAL_STALE_SECONDS = 20L
const val PRESENCE_KIND = 20481
const val PRESENCE_STALE_SECONDS = 30L
private const val PRESENCE_TAG_INFO = "kinclude/v1/call-presence-tag"
private const val PRESENCE_KEY_INFO = "kinclude/v1/call-presence-key"
private val CALL_ID = Regex("^[0-9a-f]{32}$")

data class SignalBody(val type: String, val roomId: String, val call: String, val sdp: String? = null, val candidate: String? = null)
data class ReceivedSignal(val from: String, val id: String, val body: SignalBody)

/** KithMoot call signalling profile 1, with Kinclude's `call` in the body. */
fun encodeSignal(deviceSk: ByteArray, recipient: String, body: SignalBody, now: Long): NostrEvent {
    val content = buildJsonObject {
        put("type", body.type); put("roomId", body.roomId); put("call", body.call)
        body.sdp?.let { put("sdp", it) }; body.candidate?.let { put("candidate", it) }
    }
    val inner = Events.sign(deviceSk, SIGNAL_KIND, now,
        listOf(listOf("p", recipient), listOf("call-id", body.roomId), listOf("alt", "KithMoot call signalling"), listOf("kithmoot", "1")), content.toString())
    val throwaway = Entropy.bytes(32)
    try {
        return Events.sign(throwaway, SIGNAL_WRAP_KIND, now, listOf(listOf("p", recipient), listOf("expiration", (now + SIGNAL_EXPIRY_SECONDS).toString())),
            Nip44.encrypt(inner.toCompactJson(), Nip44.conversationKey(throwaway, recipient.hexToBytes())))
    } finally { throwaway.fill(0) }
}

fun decodeSignal(wrap: NostrEvent, deviceSk: ByteArray, roomId: String, now: Long): ReceivedSignal? = runCatching {
    if (wrap.kind != SIGNAL_WRAP_KIND || wrap.content.length > 131_072 || wrap.tags.size > 16) return null
    val me = Schnorr.publicKeyHex(deviceSk)
    if (wrap.tagValue("p") != me || !Events.verify(wrap)) return null
    val inner = NostrEvent.fromJson(Json.parseToJsonElement(Nip44.decrypt(wrap.content, Nip44.conversationKey(deviceSk, wrap.pubkey.hexToBytes()))))
    if (inner.kind != SIGNAL_KIND || !Events.verify(inner) || abs(inner.createdAt - now) > SIGNAL_STALE_SECONDS) return null
    if (inner.tagValue("p") != me) return null
    val callIds = inner.tags.filter { it.firstOrNull() == "call-id" }
    if (callIds.size > 1 || (callIds.size == 1 && callIds[0].getOrNull(1) != roomId)) return null
    val b = Json.parseToJsonElement(inner.content).jsonObject
    fun s(k: String) = b[k]?.jsonPrimitive?.contentOrNull
    if (s("roomId") != roomId) return null
    val call = s("call")?.takeIf { CALL_ID.matches(it) } ?: return null
    val type = s("type")?.takeIf { it == "offer" || it == "answer" || it == "ice" } ?: return null
    val sdp = s("sdp")?.takeIf { it.length <= 65_536 }
    val candidate = s("candidate")?.takeIf { it.length <= 4_096 }
    if (type != "ice" && sdp == null) return null
    if (type == "ice" && candidate == null) return null
    ReceivedSignal(inner.pubkey, inner.id, SignalBody(type, roomId, call, sdp, candidate))
}.getOrNull()

data class Presence(val call: String, val on: Boolean, val participant: String, val device: String, val credential: NostrEvent, val video: Boolean, val at: Long)

fun presenceKeys(epochKey: ByteArray): Pair<String, ByteArray> =
    Digests.hkdfSha256(epochKey, null, PRESENCE_TAG_INFO.toByteArray(), 32).toHex() to Digests.hkdfSha256(epochKey, null, PRESENCE_KEY_INFO.toByteArray(), 32)

fun encodePresence(p: Presence, deviceSk: ByteArray, epochKey: ByteArray): NostrEvent {
    val (tag, key) = presenceKeys(epochKey)
    val body = buildJsonObject {
        put("call", p.call); put("state", if (p.on) "on" else "off"); put("participant", p.participant); put("device", p.device)
        put("credential", p.credential.toJson()); put("video", p.video); put("at", p.at)
    }
    return Events.sign(deviceSk, PRESENCE_KIND, p.at, listOf(listOf("d", tag)), Nip44.encrypt(body.toString(), key))
}

fun decodePresence(e: NostrEvent, roomId: String, epochKey: ByteArray, now: Long): Presence? = runCatching {
    val (tag, key) = presenceKeys(epochKey)
    if (e.kind != PRESENCE_KIND || e.tagValue("d") != tag || !Events.verify(e)) return null
    val p = Json.parseToJsonElement(Nip44.decrypt(e.content, key)).jsonObject
    fun s(k: String) = p[k]?.jsonPrimitive?.contentOrNull
    val call = s("call")?.takeIf { CALL_ID.matches(it) } ?: return null
    val state = s("state")?.takeIf { it == "on" || it == "off" } ?: return null
    val participant = s("participant")?.takeIf(::isPubkey) ?: return null
    val video = p["video"]?.jsonPrimitive?.booleanOrNull ?: return null
    val at = p["at"]?.jsonPrimitive?.longOrNull ?: return null
    if (s("device") != e.pubkey || at != e.createdAt || abs(at - now) > PRESENCE_STALE_SECONDS) return null
    val credential = NostrEvent.fromJson(p.getValue("credential"))
    val check = verifyDeviceCredential(credential, roomId, e.createdAt)
    if (check !is CredentialCheck.Valid || check.device != e.pubkey || check.participant != participant) return null
    Presence(call, state == "on", participant, e.pubkey, credential, video, at)
}.getOrNull()

// ---------------------------------------------------------------- the call

data class IceServer(val urls: String, val username: String? = null, val credential: String? = null)

/** One peer connection, as the call needs it. The app wraps WebRTC's
 *  callback API in these suspend functions. */
interface PeerConnectionPort {
    suspend fun createOffer(): String
    suspend fun createAnswer(): String
    suspend fun setLocal(type: String, sdp: String)
    suspend fun setRemote(type: String, sdp: String)
    suspend fun addIceCandidate(json: String)
    val hasRemote: Boolean
    val state: String
    fun close()
    var onIceCandidate: ((String) -> Unit)?
    var onRemoteMedia: ((Any) -> Unit)?
    var onStateChange: (() -> Unit)?
}

interface LocalMedia {
    fun setMuted(muted: Boolean)
    fun setCameraOff(off: Boolean)
    fun stop()
}

interface CallEnvironment {
    suspend fun startLocal(video: Boolean): LocalMedia
    fun createPeerConnection(iceServers: List<IceServer>, local: LocalMedia): PeerConnectionPort
}

data class CallPeer(val device: String, val participant: String, val video: Boolean, val connection: String, val media: Any?)

/**
 * A mesh call in a room, with the three rules of protocol §8: the join is
 * recorded first, a phone only connects to people it shows on the call,
 * and the call follows the room's key set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomCall(
    private val room: KincludeRoom,
    private val env: CallEnvironment,
    private val iceServers: List<IceServer> = emptyList(),
    private val heartbeatMs: Long = 10_000,
    private val onChange: (() -> Unit)? = null,
) {
    enum class Status { IDLE, JOINING, ON }

    @Volatile var status = Status.IDLE; private set
    @Volatile var id: String? = null; private set
    @Volatile var video = false; private set
    @Volatile var muted = false; private set
    @Volatile var cameraOff = false; private set
    @Volatile var ended: String? = null
    private var local: LocalMedia? = null

    private val engine = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engine)
    private val present = LinkedHashMap<String, Presence>()
    private class Peer(val device: String, val participant: String, val pc: PeerConnectionPort) {
        var media: Any? = null; var offered = false; val pendingIce = ArrayList<String>()
    }
    private val peers = LinkedHashMap<String, Peer>()
    private val pendingOffers = HashMap<String, SignalBody>()
    private val seenSignals = HashSet<String>()
    private var watchingEpoch: Int? = null
    private var unwatchPresence: (() -> Unit)? = null
    private var unwatchSignals: (() -> Unit)? = null
    private var unwatchRoom: (() -> Unit)? = null
    private var heartbeat: Job? = null

    private fun ctx() = room.callContext()
    private fun changed() { onChange?.invoke() }

    fun watch() {
        scope.launch {
            if (unwatchRoom == null) unwatchRoom = room.subscribe { scope.launch { onRoomChange() } }
            watchPresence()
        }
    }

    private fun watchPresence() {
        val c = ctx()
        val epoch = c.epoch
        if (epoch == null) { unwatchPresence?.invoke(); unwatchPresence = null; watchingEpoch = null; present.clear(); return }
        if (watchingEpoch == epoch.first) return
        unwatchPresence?.invoke()
        present.clear()
        watchingEpoch = epoch.first
        unwatchPresence = c.transport.subscribe(listOf(Filter.of(PRESENCE_KIND, d = presenceKeys(epoch.second).first)), { e -> scope.launch { onPresence(e) } })
    }

    private suspend fun onPresence(e: NostrEvent) {
        val c = ctx()
        val epoch = c.epoch ?: return
        val p = decodePresence(e, c.roomId, epoch.second, c.now()) ?: return
        if (p.device == c.device) return
        val held = present[p.device]
        if (held != null && held.at > p.at) return
        val arriving = held == null || !held.on || held.call != p.call
        present[p.device] = p
        val mine = id
        if (status == Status.ON && mine != null) {
            if (p.on && p.call < mine && p.participant in c.readers) { switchTo(p.call); return }
            if (arriving && p.on && p.call == mine) announce()
        }
        reconcile()
        changed()
    }

    suspend fun onCall(): List<Presence> = withContext(engine) {
        val c = ctx()
        present.values.filter { it.on && c.now() - it.at <= PRESENCE_STALE_SECONDS && it.participant in c.readers }
    }

    suspend fun peers(): List<CallPeer> = withContext(engine) {
        peers.values.map { CallPeer(it.device, it.participant, present[it.device]?.video ?: false, it.pc.state, it.media) }
    }

    /** Join, or start, the room's call. The join is recorded, and the relay
     *  has acknowledged it, before presence, signals or media go anywhere. */
    suspend fun join(withVideo: Boolean) = withContext(engine) {
        if (status != Status.IDLE) return@withContext
        if (ctx().epoch == null) throw IllegalStateException("You cannot join this call: you cannot read this room at the moment.")
        if (unwatchRoom == null) unwatchRoom = room.subscribe { scope.launch { onRoomChange() } }
        watchPresence()
        status = Status.JOINING; ended = null; changed()
        try {
            local = env.startLocal(withVideo)
            video = withVideo
            val c = ctx()
            val live = present.values.filter { it.on && c.now() - it.at <= PRESENCE_STALE_SECONDS && it.participant in c.readers }.map { it.call }.minOrNull()
            id = live ?: room.view().call?.id ?: Entropy.bytes(16).toHex()
            room.postCall(id!!, join = true, video = withVideo)
        } catch (t: Throwable) {
            local?.stop(); local = null; status = Status.IDLE; id = null; changed(); throw t
        }
        status = Status.ON
        val c = ctx()
        unwatchSignals = c.transport.subscribe(listOf(Filter.of(SIGNAL_WRAP_KIND, p = c.device)), { e -> scope.launch { onSignal(e) } })
        announce()
        heartbeat = scope.launch { while (isActive) { delay(heartbeatMs); announce(); reconcile(); changed() } }
        reconcile()
        changed()
    }

    private suspend fun switchTo(call: String) {
        val mine = id ?: return
        if (status != Status.ON || call >= mine) return
        peers.values.forEach { it.pc.close() }
        peers.clear()
        id = call
        room.postCall(call, join = true, video = video)
        announce()
        reconcile()
        changed()
    }

    private suspend fun announce(on: Boolean = true) {
        val c = ctx()
        val epoch = c.epoch ?: return
        val call = id ?: return
        c.transport.publish(encodePresence(Presence(call, on, c.me, c.device, c.credential, video && !cameraOff, c.now()), c.deviceSk, epoch.second))
    }

    private fun allowed(): Map<String, Presence> {
        val c = ctx()
        val call = id
        if (status != Status.ON || call == null || c.epoch == null) return emptyMap()
        return present.filterValues { it.on && it.call == call && c.now() - it.at <= PRESENCE_STALE_SECONDS && it.participant in c.readers }
    }

    private fun reconcile() {
        val ok = allowed()
        peers.keys.filter { it !in ok }.forEach { peers.remove(it)?.pc?.close() }
        if (status != Status.ON) return
        val me = ctx().device
        for ((device, p) in ok) {
            if (device in peers) continue
            val peer = makePeer(device, p.participant)
            if (me < device) scope.launch { offer(peer) }
            pendingOffers.remove(device)?.let { body -> scope.launch { answer(peer, body) } }
        }
    }

    private fun makePeer(device: String, participant: String): Peer {
        val pc = env.createPeerConnection(iceServers, local!!)
        val peer = Peer(device, participant, pc)
        pc.onIceCandidate = { json -> scope.launch { send(device, "ice", candidate = json) } }
        pc.onRemoteMedia = { m -> peer.media = m; changed() }
        pc.onStateChange = { changed() }
        peers[device] = peer
        return peer
    }

    private suspend fun send(device: String, type: String, sdp: String? = null, candidate: String? = null) {
        val c = ctx()
        val call = id ?: return
        c.transport.publish(encodeSignal(c.deviceSk, device, SignalBody(type, c.roomId, call, sdp, candidate), c.now()))
    }

    private suspend fun offer(peer: Peer) {
        if (peer.offered) return
        peer.offered = true
        val sdp = peer.pc.createOffer()
        peer.pc.setLocal("offer", sdp)
        send(peer.device, "offer", sdp = sdp)
    }

    private suspend fun answer(peer: Peer, body: SignalBody) {
        peer.pc.setRemote("offer", body.sdp!!)
        flushIce(peer)
        val sdp = peer.pc.createAnswer()
        peer.pc.setLocal("answer", sdp)
        send(peer.device, "answer", sdp = sdp)
    }

    private suspend fun flushIce(peer: Peer) {
        val queued = peer.pendingIce.toList().also { peer.pendingIce.clear() }
        for (c in queued) runCatching { peer.pc.addIceCandidate(c) }
    }

    private suspend fun onSignal(e: NostrEvent) {
        val c = ctx()
        val s = decodeSignal(e, c.deviceSk, c.roomId, c.now()) ?: return
        if (!seenSignals.add(s.id) || s.body.call != id) return
        val peer = peers[s.from]
        if (peer == null) {
            // An offer can beat its sender's presence; keep it until the
            // presence says who it is from. Never answer blind.
            if (s.body.type == "offer") pendingOffers[s.from] = s.body
            return
        }
        runCatching {
            when (s.body.type) {
                "offer" -> answer(peer, s.body)
                "answer" -> { peer.pc.setRemote("answer", s.body.sdp!!); flushIce(peer) }
                "ice" -> if (peer.pc.hasRemote) peer.pc.addIceCandidate(s.body.candidate!!) else peer.pendingIce += s.body.candidate!!
            }
        }
        changed()
    }

    private suspend fun onRoomChange() {
        if (status != Status.IDLE && ctx().epoch == null) { leaveNow("You can no longer read this room, so you left its call."); return }
        val before = watchingEpoch
        watchPresence()
        if (status == Status.ON && before != watchingEpoch) announce()
        reconcile()
        changed()
    }

    suspend fun setMuted(value: Boolean) = withContext(engine) { muted = value; local?.setMuted(value); changed() }
    suspend fun setCameraOff(value: Boolean) = withContext(engine) { cameraOff = value; local?.setCameraOff(value); announce(); changed() }

    suspend fun leave(reason: String? = null) = withContext(engine) { leaveNow(reason) }

    private suspend fun leaveNow(reason: String?) {
        if (status == Status.IDLE) return
        val call = id
        heartbeat?.cancel(); heartbeat = null
        unwatchSignals?.invoke(); unwatchSignals = null
        peers.values.forEach { it.pc.close() }
        peers.clear(); pendingOffers.clear()
        local?.stop(); local = null
        val wasOn = status == Status.ON
        status = Status.IDLE
        ended = reason
        changed()
        if (wasOn && call != null) {
            runCatching { announce(on = false) }
            if (ctx().epoch != null) runCatching { room.postCall(call, join = false, video = video) }
        }
        id = null
    }

    suspend fun close() {
        leave()
        withContext(engine) { unwatchPresence?.invoke(); unwatchRoom?.invoke(); unwatchPresence = null; unwatchRoom = null; watchingEpoch = null }
        scope.cancel()
    }
}
