package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.CredentialCheck
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.verifyDeviceCredential
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Room messages: KithMoot chat envelope 1 (kind 1460) with Kinclude's
 * payloads in one `kinclude` field. Kotlin twin of `src/messages.ts`
 * (protocol §3).
 */
const val KIND_CHAT = 1460

/** The epoch-0 channel every admitted device reads (protocol §3). */
const val LOBBY_CHANNEL = "kinclude-lobby"
const val MAX_TEXT_LENGTH = 2_000
const val MAX_CLOCK_SKEW_SECONDS = 300L
const val MAX_EVENTS_PER_PAYLOAD = 64

private const val CHANNEL_ID_INFO = "kithmoot/v1/channel-id/"
private const val CHANNEL_KEY_INFO = "kithmoot/v1/channel-key/"

/** One stream on the wire: its `d` tag and its NIP-44 conversation key. */
class Stream(val id: String, key: ByteArray) { val key = key.copyOf() }

/** fold-kit's `deriveChannel`: the root itself for the main chat, else HKDF. */
fun deriveChannel(rootId: String, rootKey: ByteArray, channel: String? = null): Stream {
    if (channel == null) return Stream(rootId, rootKey)
    require(channel.isNotEmpty() && channel.length <= 64) { "channel name out of range" }
    val id = Digests.hkdfSha256(rootKey, null, (CHANNEL_ID_INFO + channel).toByteArray(Charsets.UTF_8), 32).toHex()
    val key = Digests.hkdfSha256(rootKey, null, (CHANNEL_KEY_INFO + channel).toByteArray(Charsets.UTF_8), 32)
    return Stream(id, key)
}

sealed interface Payload {
    data class Statements(val events: List<NostrEvent>) : Payload
    data class Hello(val events: List<NostrEvent>) : Payload
    data class Propose(val invitee: String, val role: Role, val guardians: List<String>, val name: String?, val expires: Long) : Payload
    data class State(val event: NostrEvent) : Payload
    data class Read(val child: String, val upTo: String, val at: Long) : Payload
    data class Escrow(val epoch: Int, val copies: List<Pair<String, String>>) : Payload
    data class Emergency(val event: NostrEvent) : Payload
    data class Remove(val target: String) : Payload
    data class Call(val call: String, val join: Boolean, val video: Boolean) : Payload
}

data class RoomMessage(
    val id: String,
    val participant: String,
    val device: String,
    val credential: NostrEvent,
    val text: String,
    val sentAt: Long,
    val name: String? = null,
    val sentAtMs: Long? = null,
    val retracts: String? = null,
    val kinclude: Payload? = null,
)

fun newMessageId(): String = Entropy.bytes(16).toHex()

private val CALL_ID = Regex("^[0-9a-f]{32}$")

fun payloadToJson(p: Payload): JsonObject = buildJsonObject {
    when (p) {
        is Payload.Statements -> { put("t", "statements"); put("events", JsonArray(p.events.map { it.toJson() })) }
        is Payload.Hello -> { put("t", "hello"); put("events", JsonArray(p.events.map { it.toJson() })) }
        is Payload.Propose -> {
            put("t", "propose"); put("invitee", p.invitee); put("role", p.role.wire)
            put("guardians", JsonArray(p.guardians.sorted().map { JsonPrimitive(it) })); put("expires", p.expires)
            p.name?.let { put("name", it) }
        }
        is Payload.State -> { put("t", "state"); put("event", p.event.toJson()) }
        is Payload.Read -> { put("t", "read"); put("child", p.child); put("upTo", p.upTo); put("at", p.at) }
        is Payload.Escrow -> {
            put("t", "escrow"); put("epoch", p.epoch)
            put("copies", JsonArray(p.copies.map { (g, ct) -> buildJsonObject { put("guardian", g); put("ct", ct) } }))
        }
        is Payload.Emergency -> { put("t", "emergency"); put("event", p.event.toJson()) }
        is Payload.Remove -> { put("t", "remove"); put("target", p.target) }
        is Payload.Call -> { put("t", "call"); put("call", p.call); put("state", if (p.join) "join" else "leave"); put("video", p.video) }
    }
}

fun readPayload(raw: JsonObject): Payload? = runCatching {
    fun s(k: String) = raw[k]?.jsonPrimitive?.contentOrNull
    fun events(allowEmpty: Boolean): List<NostrEvent>? {
        val a = raw["events"]?.jsonArray ?: return null
        if ((a.isEmpty() && !allowEmpty) || a.size > MAX_EVENTS_PER_PAYLOAD) return null
        return a.map { NostrEvent.fromJson(it) }
    }
    when (s("t")) {
        "statements" -> events(false)?.let { Payload.Statements(it) }
        "hello" -> events(true)?.let { Payload.Hello(it) }
        "propose" -> {
            val invitee = s("invitee")?.takeIf(::isPubkey) ?: return null
            val role = Role.of(s("role")) ?: return null
            val guardians = raw["guardians"]?.jsonArray?.takeIf { it.size <= 8 }?.map { it.jsonPrimitive.contentOrNull?.takeIf(::isPubkey) ?: return null } ?: return null
            if ((role == Role.ADULT) != guardians.isEmpty()) return null
            val expires = raw["expires"]?.jsonPrimitive?.longOrNull ?: return null
            Payload.Propose(invitee, role, guardians.sorted(), sanitiseName(s("name")), expires)
        }
        "state" -> raw["event"]?.let { Payload.State(NostrEvent.fromJson(it)) }
        "emergency" -> raw["event"]?.let { Payload.Emergency(NostrEvent.fromJson(it)) }
        "read" -> {
            val child = s("child")?.takeIf(::isPubkey) ?: return null
            val upTo = s("upTo")?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
            Payload.Read(child, upTo, raw["at"]?.jsonPrimitive?.longOrNull ?: return null)
        }
        "escrow" -> {
            val epoch = raw["epoch"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 } ?: return null
            val copies = raw["copies"]?.jsonArray?.takeIf { it.size in 1..8 }?.map {
                val c = it.jsonObject
                val g = c["guardian"]?.jsonPrimitive?.contentOrNull?.takeIf(::isPubkey) ?: return null
                val ct = c["ct"]?.jsonPrimitive?.contentOrNull?.takeIf { x -> x.length <= 1024 } ?: return null
                g to ct
            } ?: return null
            Payload.Escrow(epoch, copies)
        }
        "remove" -> s("target")?.takeIf(::isPubkey)?.let { Payload.Remove(it) }
        "call" -> {
            val call = s("call")?.takeIf { CALL_ID.matches(it) } ?: return null
            val state = s("state")?.takeIf { it == "join" || it == "leave" } ?: return null
            val video = raw["video"]?.jsonPrimitive?.booleanOrNull ?: return null
            Payload.Call(call, state == "join", video)
        }
        else -> null
    }
}.getOrNull()

fun encodeMessage(msg: RoomMessage, stream: Stream, deviceSecretKey: ByteArray): NostrEvent {
    require(msg.text.isNotEmpty() && msg.text.length <= MAX_TEXT_LENGTH) { "a message has readable text" }
    val body = buildJsonObject {
        put("id", msg.id)
        put("participant", msg.participant)
        put("device", msg.device)
        put("credential", msg.credential.toJson())
        put("text", msg.text)
        put("sentAt", msg.sentAt)
        sanitiseName(msg.name)?.let { put("name", it) }
        msg.sentAtMs?.let { put("sentAtMs", it) }
        msg.retracts?.let { put("retracts", it) }
        msg.kinclude?.let { put("kinclude", payloadToJson(it)) }
    }
    return Events.sign(deviceSecretKey, KIND_CHAT, msg.sentAt, listOf(listOf("d", stream.id)), Nip44.encrypt(body.toString(), stream.key))
}

fun decodeMessage(e: NostrEvent, roomId: String, stream: Stream, now: Long): RoomMessage? = runCatching {
    if (e.kind != KIND_CHAT || e.tagValue("d") != stream.id || !Events.verify(e)) return null
    val raw = Json.parseToJsonElement(Nip44.decrypt(e.content, stream.key)).jsonObject
    val id = raw["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return null
    val participant = raw["participant"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
    val device = raw["device"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
    val text = raw["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH } ?: return null
    val sentAt = raw["sentAt"]?.jsonPrimitive?.longOrNull ?: return null
    val credential = NostrEvent.fromJson(raw["credential"]?.jsonObject ?: return null)
    if (device != e.pubkey || sentAt != e.createdAt || sentAt > now + MAX_CLOCK_SKEW_SECONDS) return null
    val check = verifyDeviceCredential(credential, roomId, sentAt)
    if (check !is CredentialCheck.Valid || check.device != device || check.participant != participant) return null
    val kinclude = raw["kinclude"]?.let { readPayload(it.jsonObject) ?: return null }
    val ms = raw["sentAtMs"]?.jsonPrimitive?.longOrNull?.takeIf { it / 1000 == sentAt }
    RoomMessage(id, participant, device, credential, text, sentAt, sanitiseName(raw["name"]?.jsonPrimitive?.contentOrNull), ms,
        raw["retracts"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() && it.length <= 128 }, kinclude)
}.getOrNull()
