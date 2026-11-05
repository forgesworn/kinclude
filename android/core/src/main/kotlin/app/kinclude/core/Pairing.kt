package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64

/**
 * Pairing a child's phone with a guardian, in person (protocol §7): the
 * Kotlin twin of `src/pairing.ts`. The code, the keys and the messages are
 * the same, so a guardian's phone and a child's phone pair whichever
 * implementation each runs.
 */
const val PAIRING_KIND = 20480
const val PAIRING_KEY_INFO = "kinclude/v1/pairing-key"
const val PAIRING_TAG_INFO = "kinclude/v1/pairing-tag"
const val PAIRING_PREFIX = "kinclude-pair:"

data class PairingCode(val guardian: String, val secret: String, val tier: Tier, val childName: String? = null, val guardianName: String? = null)

fun encodePairingCode(c: PairingCode): String {
    val body = buildJsonObject {
        put("g", c.guardian); put("s", c.secret); put("t", c.tier.n)
        c.childName?.let { put("n", it) }; c.guardianName?.let { put("m", it) }
    }
    return PAIRING_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray(Charsets.UTF_8))
}

fun decodePairingCode(raw: String): PairingCode? = runCatching {
    val trimmed = raw.trim()
    if (!trimmed.startsWith(PAIRING_PREFIX)) return null
    val b = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(trimmed.removePrefix(PAIRING_PREFIX)), Charsets.UTF_8)).jsonObject
    val g = b["g"]?.jsonPrimitive?.contentOrNull?.takeIf(::isPubkey) ?: return null
    val s = b["s"]?.jsonPrimitive?.contentOrNull?.takeIf { Regex("^[0-9a-f]{32}$").matches(it) } ?: return null
    val t = Tier.of(b["t"]?.jsonPrimitive?.intOrNull) ?: return null
    PairingCode(g, s, t, sanitiseName(b["n"]?.jsonPrimitive?.contentOrNull), sanitiseName(b["m"]?.jsonPrimitive?.contentOrNull))
}.getOrNull()

private fun pairingKeys(secret: String): Pair<ByteArray, String> {
    val s = secret.hexToBytes()
    return Digests.hkdfSha256(s, null, PAIRING_KEY_INFO.toByteArray(), 32) to Digests.hkdfSha256(s, null, PAIRING_TAG_INFO.toByteArray(), 32).toHex()
}

private data class PairingMessage(val fromChild: Boolean, val pk: String, val name: String?, val inbox: String?, val events: List<NostrEvent>)

private fun seal(secret: String, m: PairingMessage, signerSk: ByteArray, now: Long): NostrEvent {
    val (key, tag) = pairingKeys(secret)
    val body = buildJsonObject {
        put("from", if (m.fromChild) "child" else "guardian"); put("pk", m.pk)
        put("events", JsonArray(m.events.map { it.toJson() }))
        m.name?.let { put("name", it) }; m.inbox?.let { put("inbox", it) }
    }
    return Events.sign(signerSk, PAIRING_KIND, now, listOf(listOf("d", tag)), Nip44.encrypt(body.toString(), key))
}

private fun open(secret: String, e: NostrEvent): PairingMessage? = runCatching {
    val (key, tag) = pairingKeys(secret)
    if (e.kind != PAIRING_KIND || e.tagValue("d") != tag) return null
    val raw = Json.parseToJsonElement(Nip44.decrypt(e.content, key)).jsonObject
    val from = raw["from"]?.jsonPrimitive?.contentOrNull?.takeIf { it == "child" || it == "guardian" } ?: return null
    val pk = raw["pk"]?.jsonPrimitive?.contentOrNull?.takeIf(::isPubkey) ?: return null
    val events = raw["events"]?.jsonArray?.takeIf { it.size <= 32 }?.map { NostrEvent.fromJson(it) } ?: return null
    val inbox = raw["inbox"]?.jsonPrimitive?.contentOrNull
    if (inbox != null && !isPubkey(inbox)) return null
    PairingMessage(from == "child", pk, sanitiseName(raw["name"]?.jsonPrimitive?.contentOrNull), inbox, events)
}.getOrNull()

data class PairedChild(val pk: String, val name: String?, val inbox: String, val events: List<NostrEvent>)

class PairingOffer(val code: String, val paired: CompletableDeferred<PairedChild>, val cancel: () -> Unit)

/** The guardian's side: make a code, wait for the child's phone, answer. */
fun offerPairing(transport: RelayTransport, guardian: KincludeSigner, tier: Tier, now: () -> Long,
    guardianName: String? = null, childName: String? = null, scope: CoroutineScope = CoroutineScope(Dispatchers.Default)): PairingOffer {
    val secret = Entropy.bytes(16).toHex()
    val code = encodePairingCode(PairingCode(guardian.pubkey, secret, tier, childName, guardianName))
    val throwaway = Entropy.bytes(32)
    val paired = CompletableDeferred<PairedChild>()
    var stop: () -> Unit = {}
    stop = transport.subscribe(listOf(Filter.of(PAIRING_KIND, d = pairingKeys(secret).second)), { event ->
        scope.launch {
            val msg = open(secret, event) ?: return@launch
            if (!msg.fromChild || msg.inbox == null || paired.isCompleted) return@launch
            val child = msg.pk
            val up = msg.events.mapNotNull(::readLinkStatement).firstOrNull { it.type == LinkType.DEPENDANT_OF && it.signer == child && it.other == guardian.pubkey } ?: return@launch
            val genesis = msg.events.mapNotNull(::readTier).firstOrNull { it.statement.child == child && it.statement.seq == 0 && it.signer == child } ?: return@launch
            val mine = mutableListOf(guardian.sign(guardianOfUnsigned(child, now())))
            val family = FamilyGraph().also { it.addAll(msg.events); it.addAll(mine); it.add(up.event) }
            val ledger = TierLedger(family).also { it.addAll(msg.events) }
            if (ledger.current(child) == null) {
                if (genesis.statement.tier != tier || genesis.statement.prev != TIER_GENESIS) return@launch
                mine += guardian.sign(tierUnsigned(genesis.statement, now(), msg.name))
            }
            transport.publish(seal(secret, PairingMessage(false, guardian.pubkey, guardianName, null, mine), throwaway, now()))
            stop()
            paired.complete(PairedChild(child, msg.name, msg.inbox, msg.events + mine))
        }
    })
    return PairingOffer(code, paired) { stop() }
}

data class Paired(val guardian: String, val guardianName: String?, val events: List<NostrEvent>)

/** The child's side: read the code, send this phone's halves, wait. */
suspend fun acceptPairing(transport: RelayTransport, child: KincludeSigner, inboxSk: ByteArray, code: String, now: () -> Long,
    childName: String? = null, existing: List<NostrEvent> = emptyList(), timeoutMs: Long = 120_000): Paired {
    val c = decodePairingCode(code) ?: throw IllegalArgumentException("That is not a Kinclude pairing code.")
    require(c.guardian != child.pubkey) { "A phone cannot pair with its own person." }
    val family = FamilyGraph().also { it.addAll(existing) }
    val ledger = TierLedger(family).also { it.addAll(existing) }
    val events = mutableListOf(child.sign(dependantOfUnsigned(c.guardian, now())))
    if (ledger.current(child.pubkey) != null) events += family.statementsFor(child.pubkey) + ledger.chainEvents(child.pubkey)
    else events += child.sign(tierUnsigned(TierStatement(child.pubkey, c.tier, 0, TIER_GENESIS), now(), childName))
    val answer = CompletableDeferred<Paired>()
    val stop = transport.subscribe(listOf(Filter.of(PAIRING_KIND, d = pairingKeys(c.secret).second)), { e ->
        val msg = open(c.secret, e) ?: return@subscribe
        if (msg.fromChild || msg.pk != c.guardian) return@subscribe
        if (msg.events.mapNotNull(::readLinkStatement).none { it.type == LinkType.GUARDIAN_OF && it.signer == c.guardian && it.other == child.pubkey }) return@subscribe
        answer.complete(Paired(c.guardian, msg.name, events + msg.events))
    })
    try {
        kotlinx.coroutines.delay(20)
        transport.publish(seal(c.secret, PairingMessage(true, child.pubkey, childName, pubkeyOf(inboxSk), events), inboxSk, now()))
        return withTimeout(timeoutMs) { answer.await() }
    } finally { stop() }
}
