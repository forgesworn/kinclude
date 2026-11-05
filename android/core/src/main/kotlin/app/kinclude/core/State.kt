package app.kinclude.core

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The room's state, signed by its authority: the Kotlin twin of
 * `src/state.ts` (protocol §4). The JSON is written field for field as the
 * TypeScript writes it, so both read each other's.
 */
const val STATE_KIND = 30078
const val STATE_PREFIX = "kinclude/state/"
const val MAX_MEMBERS = 64

enum class MemberRole(val wire: String) {
    ADULT("adult"), CHILD("child"), GUARDIAN("guardian");
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

data class StateMember(
    val pk: String,
    val role: MemberRole,
    val name: String? = null,
    val by: String,
    val at: Long,
    val approvals: List<NostrEvent> = emptyList(),
    val links: List<NostrEvent> = emptyList(),
)

data class RoomState(
    val room: String,
    val seq: Int,
    val prev: String?,
    val name: String,
    val epoch: Int,
    val members: List<StateMember>,
    val readers: List<String>,
    val removed: List<String>,
) {
    fun member(pk: String): StateMember? = members.firstOrNull { it.pk == pk }
    val children: List<StateMember> get() = members.filter { it.role == MemberRole.CHILD }
}

data class SignedState(val state: RoomState, val event: NostrEvent)

/** Display names as the TypeScript `sanitiseDisplayName` keeps them. */
fun sanitiseName(raw: String?): String? = sanitiseReason(raw, 64)

fun stateToJson(s: RoomState): JsonObject = buildJsonObject {
    put("v", 1)
    put("room", s.room)
    put("seq", s.seq)
    put("prev", s.prev?.let { JsonPrimitive(it) } ?: JsonNull)
    put("name", s.name)
    put("epoch", s.epoch)
    put("members", buildJsonArray {
        s.members.forEach { m ->
            add(buildJsonObject {
                put("pk", m.pk)
                put("role", m.role.wire)
                put("by", m.by)
                put("at", m.at)
                put("approvals", JsonArray(m.approvals.map { it.toJson() }))
                put("links", JsonArray(m.links.map { it.toJson() }))
                m.name?.let { put("name", it) }
            })
        }
    })
    put("readers", JsonArray(s.readers.sorted().map { JsonPrimitive(it) }))
    put("removed", JsonArray(s.removed.sorted().map { JsonPrimitive(it) }))
}

fun parseState(raw: JsonObject): RoomState? = runCatching {
    if (raw["v"]?.jsonPrimitive?.longOrNull != 1L) return null
    val room = raw["room"]?.jsonPrimitive?.contentOrNull?.takeIf(::isPubkey) ?: return null
    val seq = raw["seq"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return null
    val prevEl = raw["prev"]
    val prev = if (prevEl == null || prevEl is JsonNull) null else prevEl.jsonPrimitive.contentOrNull
    if (if (seq == 0) prev != null else !isPubkey(prev)) return null
    val name = sanitiseName(raw["name"]?.jsonPrimitive?.contentOrNull) ?: "Family room"
    val epoch = raw["epoch"]?.jsonPrimitive?.longOrNull?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: return null
    val membersRaw = raw["members"]?.jsonArray ?: return null
    if (membersRaw.isEmpty() || membersRaw.size > MAX_MEMBERS) return null
    val seen = HashSet<String>()
    val members = membersRaw.map { el ->
        val m = el.jsonObject
        val pk = m["pk"]?.jsonPrimitive?.contentOrNull?.takeIf { isPubkey(it) && seen.add(it) } ?: return null
        val role = MemberRole.of(m["role"]?.jsonPrimitive?.contentOrNull) ?: return null
        val by = m["by"]?.jsonPrimitive?.contentOrNull?.takeIf(::isPubkey) ?: return null
        val at = m["at"]?.jsonPrimitive?.longOrNull ?: return null
        val approvals = m["approvals"]?.jsonArray?.takeIf { it.size <= 32 }?.map { NostrEvent.fromJson(it) } ?: return null
        val links = m["links"]?.jsonArray?.takeIf { it.size <= 32 }?.map { NostrEvent.fromJson(it) } ?: return null
        StateMember(pk, role, sanitiseName(m["name"]?.jsonPrimitive?.contentOrNull), by, at, approvals, links)
    }
    fun list(key: String): List<String>? = raw[key]?.jsonArray?.takeIf { it.size <= MAX_MEMBERS * 2 }
        ?.map { it.jsonPrimitive.contentOrNull?.takeIf(::isPubkey) ?: return null }?.distinct()?.sorted()
    val readers = list("readers") ?: return null
    val removed = list("removed") ?: return null
    if (readers.any { it !in seen }) return null
    RoomState(room, seq, prev, name, epoch, members, readers, removed)
}.getOrNull()

fun signState(authoritySecretKey: ByteArray, state: RoomState, now: Long): NostrEvent {
    val content = stateToJson(state)
    requireNotNull(parseState(content)) { "malformed room state" }
    return Events.sign(authoritySecretKey, STATE_KIND, now,
        listOf(listOf("d", STATE_PREFIX + state.room), listOf("seq", state.seq.toString())), content.toString())
}

fun readState(e: NostrEvent, room: String, authority: String): SignedState? = runCatching {
    if (e.kind != STATE_KIND || e.pubkey != authority || !Events.verify(e)) return null
    if (e.tagValue("d") != STATE_PREFIX + room) return null
    val s = parseState(Json.parseToJsonElement(e.content).jsonObject) ?: return null
    if (s.room != room || e.tagValue("seq") != s.seq.toString()) return null
    SignedState(s, e)
}.getOrNull()
