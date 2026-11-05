package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Keys a phone derives from the one secret it keeps (protocol §1). */
const val DEVICE_KEY_INFO = "kinclude/v1/room-device:"
const val INBOX_KEY_INFO = "kinclude/v1/inbox"

fun roomDeviceKey(phoneSecret: ByteArray, roomId: String): ByteArray {
    require(isPubkey(roomId)) { "room id is lower-case hex" }
    return Digests.hkdfSha256(phoneSecret, null, (DEVICE_KEY_INFO + roomId).toByteArray(Charsets.UTF_8), 32)
}

fun inboxKey(phoneSecret: ByteArray): ByteArray = Digests.hkdfSha256(phoneSecret, null, INBOX_KEY_INFO.toByteArray(Charsets.UTF_8), 32)

const val GIFT_WRAP_KIND = 1059
const val SEAL_KIND = 13
const val INVITATION_RUMOR_KIND = 1480
const val FORWARD_RUMOR_KIND = 1481
const val FAMILY_RUMOR_KIND = 1482
const val WRAP_JITTER_SECONDS = 2L * 24 * 60 * 60

data class Rumor(val kind: Int, val createdAt: Long, val content: String, val pubkey: String)

/** NIP-59: a seal signed by the sender around an unsigned rumor, inside a
 *  wrap from a throwaway key. Byte for byte what `wrapGift` in
 *  `src/invite.ts` makes. */
suspend fun wrapGift(sender: KincludeSigner, recipient: String, kind: Int, content: String, now: Long): NostrEvent {
    if (!sender.canSeal) throw SealRefused()
    fun jitter() = now - (Math.random() * WRAP_JITTER_SECONDS).toLong()
    val id = Events.eventId(sender.pubkey, now, kind, emptyList(), content)
    val rumor = buildJsonObject {
        put("kind", kind); put("created_at", now); put("tags", JsonArray(emptyList()))
        put("content", content); put("pubkey", sender.pubkey); put("id", id)
    }
    val seal = sender.signEvent(SEAL_KIND, jitter(), emptyList(), sender.nip44Encrypt(recipient, rumor.toString()))
    val throwaway = Entropy.bytes(32)
    try {
        val key = Nip44.conversationKey(throwaway, recipient.hexToBytes())
        return Events.sign(throwaway, GIFT_WRAP_KIND, jitter(), listOf(listOf("p", recipient)), Nip44.encrypt(seal.toCompactJson(), key))
    } finally { throwaway.fill(0) }
}

suspend fun unwrapGift(wrap: NostrEvent, recipient: KincludeSigner): Pair<Rumor, String>? = runCatching {
    if (!recipient.canSeal || wrap.kind != GIFT_WRAP_KIND || !Events.verify(wrap)) return null
    if (wrap.tagValue("p") != recipient.pubkey) return null
    val seal = NostrEvent.fromJson(Json.parseToJsonElement(recipient.nip44Decrypt(wrap.pubkey, wrap.content)))
    if (seal.kind != SEAL_KIND || !Events.verify(seal)) return null
    val r = Json.parseToJsonElement(recipient.nip44Decrypt(seal.pubkey, seal.content)).jsonObject
    val kind = r["kind"]!!.jsonPrimitive.content.toInt()
    val createdAt = r["created_at"]!!.jsonPrimitive.longOrNull!!
    val content = r["content"]!!.jsonPrimitive.content
    val pubkey = r["pubkey"]!!.jsonPrimitive.content
    val tags = r["tags"]!!.jsonArray.map { t -> t.jsonArray.map { it.jsonPrimitive.content } }
    if (pubkey != seal.pubkey) return null
    if (Events.eventId(pubkey, createdAt, kind, tags, content) != r["id"]!!.jsonPrimitive.content) return null
    Rumor(kind, createdAt, content, pubkey) to seal.pubkey
}.getOrNull()

data class InvitationEnvelope(
    val room: String,
    val name: String,
    val bearer: String,
    val authority: String,
    val invitee: String,
    val role: Role,
    val guardians: List<String>,
    val fromName: String? = null,
    val relays: List<String>? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("v", 1); put("room", room); put("name", name); put("bearer", bearer); put("authority", authority)
        put("invitee", invitee); put("role", role.wire); put("guardians", JsonArray(guardians.sorted().map { JsonPrimitive(it) }))
        fromName?.let { put("fromName", it) }
        relays?.let { put("relays", JsonArray(it.map { r -> JsonPrimitive(r) })) }
    }

    companion object {
        fun read(raw: JsonObject): InvitationEnvelope? = runCatching {
            fun s(k: String) = raw[k]?.jsonPrimitive?.contentOrNull
            if (raw["v"]?.jsonPrimitive?.longOrNull != 1L) return null
            val room = s("room")?.takeIf(::isPubkey) ?: return null
            val authority = s("authority")?.takeIf(::isPubkey) ?: return null
            val invitee = s("invitee")?.takeIf(::isPubkey) ?: return null
            val bearer = s("bearer")?.takeIf(::isPubkey) ?: return null
            val role = Role.of(s("role")) ?: return null
            val guardians = raw["guardians"]?.jsonArray?.takeIf { it.size <= 8 }?.map { it.jsonPrimitive.contentOrNull?.takeIf(::isPubkey) ?: return null } ?: return null
            if ((role == Role.ADULT) != guardians.isEmpty()) return null
            val relays = raw["relays"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf { r -> Regex("^wss?://").containsMatchIn(r) && r.length < 256 } }?.takeIf { it.size <= 8 }
            InvitationEnvelope(room, sanitiseName(s("name")) ?: "A Kinclude room", bearer, authority, invitee, role, guardians.sorted(), sanitiseName(s("fromName")), relays)
        }.getOrNull()
    }
}

suspend fun wrapInvitation(sender: KincludeSigner, recipient: String, envelope: InvitationEnvelope, now: Long): NostrEvent =
    wrapGift(sender, recipient, INVITATION_RUMOR_KIND, envelope.toJson().toString(), now)

suspend fun unwrapInvitation(wrap: NostrEvent, recipient: KincludeSigner): Pair<InvitationEnvelope, String>? {
    val (rumor, sender) = unwrapGift(wrap, recipient) ?: return null
    if (rumor.kind != INVITATION_RUMOR_KIND) return null
    val env = runCatching { InvitationEnvelope.read(Json.parseToJsonElement(rumor.content).jsonObject) }.getOrNull() ?: return null
    return env to sender
}

fun pubkeyOf(secretKey: ByteArray): String = Schnorr.publicKeyHex(secretKey)
