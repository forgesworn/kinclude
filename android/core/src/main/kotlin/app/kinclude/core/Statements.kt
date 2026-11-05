package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent

/**
 * Statements: what a person signs with their identity. Kotlin twin of
 * `src/statements.ts`; `docs/protocol.md` §2 is the wire. Every statement is
 * kind 30078, data in tags, a readable sentence in content, and never
 * published bare.
 */
const val STATEMENT_KIND = 30078

object LinkPrefix {
    const val GUARDIAN_OF = "kindependence/dependant/"
    const val DEPENDANT_OF = "kindependence/guardian/"
    const val UNLINK = "kindependence/unlink/"
}

object KincludePrefix {
    const val APPROVAL = "kinclude/approval/"
    const val TIER = "kinclude/tier/"
    const val EMERGENCY = "kinclude/emergency/"
}

const val APPROVAL_TTL_SECONDS = 7L * 24 * 60 * 60
const val TIER_GENESIS = "genesis"
const val TIER_DIGEST_LABEL = "kinclude/v1/tier"

private val HEX64 = Regex("^[0-9a-f]{64}$")

fun isPubkey(value: String?): Boolean = value != null && HEX64.matches(value)

/** An event with everything but the signature: what a signer is handed. */
data class Unsigned(val kind: Int, val createdAt: Long, val tags: List<List<String>>, val content: String)

suspend fun KincludeSigner.sign(u: Unsigned): NostrEvent = signEvent(u.kind, u.createdAt, u.tags, u.content)

private fun NostrEvent.single(name: String): String? {
    val found = tags.filter { it.firstOrNull() == name }
    if (found.size != 1 || found[0].size != 2) return null
    return found[0][1]
}

private fun baseCheck(e: NostrEvent): Boolean = e.kind == STATEMENT_KIND && Events.verify(e)

private val UNSAFE = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")

/** Strip control, bidi and zero-width characters, collapse spaces, cap. */
fun sanitiseReason(raw: String?, max: Int = 280): String? {
    if (raw == null) return null
    val cleaned = raw.replace(UNSAFE, " ").replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) return null
    val points = cleaned.codePoints().toArray()
    return if (points.size <= max) cleaned else String(points, 0, max)
}

// ---------------------------------------------------------------- links

enum class LinkType { GUARDIAN_OF, DEPENDANT_OF, UNLINK }

data class LinkStatement(val type: LinkType, val signer: String, val other: String, val at: Long, val event: NostrEvent)

private fun linkUnsigned(prefix: String, other: String, content: String, now: Long): Unsigned {
    require(isPubkey(other)) { "a link names a lower-case hex pubkey" }
    return Unsigned(STATEMENT_KIND, now, listOf(listOf("d", prefix + other), listOf("p", other)), content)
}

fun guardianOfUnsigned(dependant: String, now: Long) = linkUnsigned(LinkPrefix.GUARDIAN_OF, dependant, "I am a guardian of this person.", now)
fun dependantOfUnsigned(guardian: String, now: Long) = linkUnsigned(LinkPrefix.DEPENDANT_OF, guardian, "This person is my guardian.", now)
fun unlinkUnsigned(other: String, now: Long) = linkUnsigned(LinkPrefix.UNLINK, other, "This person is no longer linked to me as guardian or dependant.", now)

fun readLinkStatement(e: NostrEvent): LinkStatement? {
    if (!baseCheck(e)) return null
    val d = e.single("d") ?: return null
    val p = e.single("p")
    if (!isPubkey(p)) return null
    for ((prefix, type) in listOf(LinkPrefix.GUARDIAN_OF to LinkType.GUARDIAN_OF, LinkPrefix.DEPENDANT_OF to LinkType.DEPENDANT_OF, LinkPrefix.UNLINK to LinkType.UNLINK)) {
        if (d == prefix + p) {
            if (p == e.pubkey) return null
            return LinkStatement(type, e.pubkey, p!!, e.createdAt, e)
        }
    }
    return null
}

// ---------------------------------------------------------------- approvals

enum class Role(val wire: String) { ADULT("adult"), CHILD("child") ;
    companion object { fun of(s: String?) = entries.firstOrNull { it.wire == s } }
}

data class Approval(
    val room: String,
    val invitee: String,
    val child: String,
    val role: Role,
    val guardians: List<String>,
    val expires: Long,
) {
    /** What both halves must agree on. */
    val key: String get() = listOf(room, invitee, child, role.wire, guardians.sorted().joinToString(","), expires.toString()).joinToString("|")
}

fun approvalUnsigned(a: Approval, now: Long, name: String? = null): Unsigned {
    for (pk in listOf(a.room, a.invitee, a.child) + a.guardians) require(isPubkey(pk)) { "an approval names lower-case hex ids" }
    require(a.role != Role.ADULT || a.guardians.isEmpty()) { "an adult newcomer has no guardians to name" }
    require(a.role != Role.CHILD || a.guardians.isNotEmpty()) { "a child newcomer comes with their guardians" }
    require(a.expires > now) { "an approval expires after it is made" }
    val who = sanitiseReason(name, 64) ?: "this person"
    return Unsigned(
        STATEMENT_KIND, now,
        listOf(
            listOf("d", "${KincludePrefix.APPROVAL}${a.room}/${a.invitee}/${a.child}"),
            listOf("p", a.invitee), listOf("room", a.room), listOf("child", a.child), listOf("role", a.role.wire),
        ) + a.guardians.sorted().map { listOf("guardian", it) } + listOf(listOf("expires", a.expires.toString())),
        if (a.role == Role.CHILD) "I agree to $who joining this Kinclude room, with their guardians able to see it as their tier allows."
        else "I agree to $who joining this Kinclude room.",
    )
}

data class SignedApproval(val approval: Approval, val signer: String, val at: Long, val event: NostrEvent)

private val KNOWN_APPROVAL_TAGS = setOf("d", "p", "room", "child", "role", "guardian", "expires")

fun readApproval(e: NostrEvent): SignedApproval? {
    if (!baseCheck(e)) return null
    val invitee = e.single("p")
    val room = e.single("room")
    val child = e.single("child")
    if (!isPubkey(invitee) || !isPubkey(room) || !isPubkey(child)) return null
    if (e.single("d") != "${KincludePrefix.APPROVAL}$room/$invitee/$child") return null
    val role = Role.of(e.single("role")) ?: return null
    val expires = e.single("expires")?.takeIf { Regex("^[0-9]{1,15}$").matches(it) }?.toLong() ?: return null
    val gTags = e.tags.filter { it.firstOrNull() == "guardian" }
    if (gTags.any { it.size != 2 || !isPubkey(it[1]) }) return null
    val guardians = gTags.map { it[1] }.sorted()
    if (guardians.toSet().size != guardians.size) return null
    if ((role == Role.ADULT) != guardians.isEmpty()) return null
    if (invitee in guardians) return null
    if (e.tags.any { it.firstOrNull() !in KNOWN_APPROVAL_TAGS }) return null
    return SignedApproval(Approval(room!!, invitee!!, child!!, role, guardians, expires), e.pubkey, e.createdAt, e)
}

// ---------------------------------------------------------------- tiers

/** 1 First messages, 2 Growing up, 3 Nearly grown. */
enum class Tier(val n: Int, val title: String) {
    FIRST_MESSAGES(1, "First messages"),
    GROWING_UP(2, "Growing up"),
    NEARLY_GROWN(3, "Nearly grown");

    companion object { fun of(n: Int?) = entries.firstOrNull { it.n == n } }
}

data class TierStatement(val child: String, val tier: Tier, val seq: Int, val prev: String)

fun tierDigest(t: TierStatement): String =
    Digests.sha256("$TIER_DIGEST_LABEL:${t.child}:${t.seq}:${t.tier.n}:${t.prev}".toByteArray(Charsets.UTF_8)).toHex()

fun tierUnsigned(t: TierStatement, now: Long, name: String? = null): Unsigned {
    require(isPubkey(t.child) && t.seq >= 0) { "malformed tier statement" }
    require((t.seq == 0) == (t.prev == TIER_GENESIS)) { "only the first tier statement starts the chain" }
    require(t.prev == TIER_GENESIS || isPubkey(t.prev)) { "prev is a digest" }
    val who = sanitiseReason(name, 64) ?: "This child"
    return Unsigned(
        STATEMENT_KIND, now,
        listOf(
            listOf("d", "${KincludePrefix.TIER}${t.child}"), listOf("p", t.child), listOf("tier", t.tier.n.toString()),
            listOf("seq", t.seq.toString()), listOf("prev", t.prev),
        ),
        "$who's oversight is now: ${t.tier.title}. We agreed this together.",
    )
}

data class SignedTier(val statement: TierStatement, val digest: String, val signer: String, val at: Long, val event: NostrEvent)

fun readTier(e: NostrEvent): SignedTier? {
    if (!baseCheck(e)) return null
    val child = e.single("p")
    if (!isPubkey(child) || e.single("d") != "${KincludePrefix.TIER}$child") return null
    val tierRaw = e.single("tier") ?: return null
    val tier = Tier.of(tierRaw.toIntOrNull()) ?: return null
    if (tierRaw != tier.n.toString()) return null
    val seqRaw = e.single("seq")?.takeIf { Regex("^(0|[1-9][0-9]{0,8})$").matches(it) } ?: return null
    val seq = seqRaw.toInt()
    val prev = e.single("prev") ?: return null
    if ((seq == 0) != (prev == TIER_GENESIS)) return null
    if (prev != TIER_GENESIS && !isPubkey(prev)) return null
    if (e.tags.size != 5) return null
    val s = TierStatement(child!!, tier, seq, prev)
    return SignedTier(s, tierDigest(s), e.pubkey, e.createdAt, e)
}

// ---------------------------------------------------------------- emergency

data class EmergencyNotice(val room: String, val child: String, val epoch: Int, val reason: String? = null)

fun emergencyUnsigned(n: EmergencyNotice, now: Long): Unsigned {
    require(isPubkey(n.room) && isPubkey(n.child) && n.epoch >= 1) { "malformed emergency notice" }
    return Unsigned(
        STATEMENT_KIND, now,
        listOf(
            listOf("d", "${KincludePrefix.EMERGENCY}${n.room}/${n.child}/${n.epoch}"), listOf("p", n.child),
            listOf("room", n.room), listOf("epoch", n.epoch.toString()),
        ),
        sanitiseReason(n.reason) ?: "I opened these messages in an emergency.",
    )
}

data class SignedEmergency(val notice: EmergencyNotice, val signer: String, val at: Long, val event: NostrEvent)

fun readEmergency(e: NostrEvent): SignedEmergency? {
    if (!baseCheck(e)) return null
    val child = e.single("p")
    val room = e.single("room")
    val epochRaw = e.single("epoch")
    if (!isPubkey(child) || !isPubkey(room) || epochRaw == null || !Regex("^[1-9][0-9]{0,8}$").matches(epochRaw)) return null
    val epoch = epochRaw.toInt()
    if (e.single("d") != "${KincludePrefix.EMERGENCY}$room/$child/$epoch") return null
    if (e.tags.size != 4) return null
    return SignedEmergency(EmergencyNotice(room!!, child!!, epoch, sanitiseReason(e.content)), e.pubkey, e.createdAt, e)
}
