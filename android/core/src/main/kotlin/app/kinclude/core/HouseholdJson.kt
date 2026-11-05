package app.kinclude.core

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A household on disk: JSON in the same shape the TypeScript keeps it, so a
 * phone's record reads back the same. Secrets are in it (the phone secret,
 * room secrets, a room authority's key); the app keeps the file in its
 * private storage and encrypts it with a key from the Android keystore.
 */
object HouseholdJson {
    fun write(d: HouseholdData): String = buildJsonObject {
        put("v", 1)
        put("name", d.name)
        put("kind", if (d.child) "child" else "adult")
        put("phoneSecret", d.phoneSecret)
        put("family", JsonArray(d.family.map { it.toJson() }))
        put("children", JsonArray(d.children.map { c -> buildJsonObject { put("pk", c.pk); put("inbox", c.inbox); c.name?.let { put("name", it) } } }))
        put("guardians", JsonArray(d.guardians.map { g -> buildJsonObject { put("pk", g.pk); g.name?.let { put("name", it) } } }))
        put("contacts", JsonArray(d.contacts.map { c -> buildJsonObject { put("pk", c.pk); put("tier", if (c.kin) "kin" else "kith"); c.name?.let { put("name", it) } } }))
        put("rooms", JsonArray(d.rooms.map(::roomJson)))
        put("handled", JsonArray(d.handled.takeLast(2000).map { JsonPrimitive(it) }))
        put("invites", JsonArray(d.invites.map { i ->
            buildJsonObject {
                put("id", i.id); put("envelope", i.envelope.toJson()); put("sender", i.sender)
                if (i.approvals.isNotEmpty()) put("approvals", JsonArray(i.approvals.map { it.toJson() }))
                i.forChild?.let { put("forChild", it) }
            }
        }))
    }.toString()

    private fun roomJson(s: RoomSecrets): JsonObject = buildJsonObject {
        put("secret", s.secret)
        put("authority", s.authority)
        s.authoritySk?.let { put("authoritySk", it) }
        put("epochs", buildJsonObject { s.epochs.toSortedMap().forEach { (n, v) -> put(n.toString(), v) } })
        if (s.issued.isNotEmpty()) put("issued", buildJsonObject { s.issued.forEach { (k, v) -> put(k, v) } })
    }

    fun read(text: String): HouseholdData? = runCatching {
        val o = Json.parseToJsonElement(text).jsonObject
        fun JsonElement.s(k: String) = jsonObject[k]?.jsonPrimitive?.contentOrNull
        fun arr(k: String) = o[k]?.jsonArray ?: JsonArray(emptyList())
        HouseholdData(
            name = o.s("name") ?: "Me",
            child = o.s("kind") == "child",
            phoneSecret = o.s("phoneSecret")!!,
            family = arr("family").map { NostrEvent.fromJson(it) },
            children = arr("children").map { KnownChild(it.s("pk")!!, it.s("name"), it.s("inbox")!!) },
            guardians = arr("guardians").map { KnownGuardian(it.s("pk")!!, it.s("name")) },
            contacts = arr("contacts").map { Contact(it.s("pk")!!, it.s("name"), it.s("tier") != "kith") },
            rooms = arr("rooms").map { r ->
                val j = r.jsonObject
                RoomSecrets(
                    r.s("secret")!!, r.s("authority")!!, r.s("authoritySk"),
                    j["epochs"]?.jsonObject?.mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to v.jsonPrimitive.content } }?.toMap() ?: emptyMap(),
                    j["issued"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                )
            },
            handled = arr("handled").mapNotNull { it.jsonPrimitive.contentOrNull },
            invites = arr("invites").mapNotNull { i ->
                val env = InvitationEnvelope.read(i.jsonObject["envelope"]!!.jsonObject) ?: return@mapNotNull null
                PendingInvite(i.s("id")!!, env, i.s("sender")!!,
                    i.jsonObject["approvals"]?.jsonArray?.map { NostrEvent.fromJson(it) } ?: emptyList(), i.s("forChild"))
            },
        )
    }.getOrNull()
}
