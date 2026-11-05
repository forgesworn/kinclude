package app.kinclude.core

import dev.forgesworn.kithmoot.protocol.NostrEvent

/**
 * Who is whose guardian, a child's tier, and which approvals are complete:
 * the Kotlin twin of `src/family.ts`. Nothing is trusted for where it came
 * from; every statement is checked by signature as it is added.
 */
class FamilyGraph {
    private val newest = LinkedHashMap<String, LinkStatement>()

    fun add(e: NostrEvent): Boolean {
        val s = readLinkStatement(e) ?: return false
        val key = "${s.type}:${s.signer}:${s.other}"
        val held = newest[key]
        if (held != null && (held.at > s.at || (held.at == s.at && held.event.id <= s.event.id))) return true
        newest[key] = s
        return true
    }

    fun addAll(events: Iterable<NostrEvent>) { events.forEach { add(it) } }

    private fun get(type: LinkType, signer: String, other: String) = newest["$type:$signer:$other"]

    fun isGuardian(guardian: String, child: String): Boolean {
        val down = get(LinkType.GUARDIAN_OF, guardian, child) ?: return false
        val up = get(LinkType.DEPENDANT_OF, child, guardian) ?: return false
        val lastCut = maxOf(get(LinkType.UNLINK, guardian, child)?.at ?: -1, get(LinkType.UNLINK, child, guardian)?.at ?: -1)
        return down.at > lastCut && up.at > lastCut
    }

    fun guardiansOf(child: String): List<String> =
        newest.values.filter { it.type == LinkType.GUARDIAN_OF && it.other == child && isGuardian(it.signer, child) }.map { it.signer }.sorted()

    fun childrenOf(guardian: String): List<String> =
        newest.values.filter { it.type == LinkType.GUARDIAN_OF && it.signer == guardian && isGuardian(guardian, it.other) }.map { it.other }.sorted()

    fun isChild(pk: String): Boolean = guardiansOf(pk).isNotEmpty()

    fun statementsFor(child: String): List<NostrEvent> =
        newest.values.filter { it.signer == child || it.other == child }.map { it.event }
            .sortedWith(compareBy<NostrEvent> { it.createdAt }.thenBy { it.id })
}

data class TierPosition(val tier: Tier, val seq: Int, val digest: String, val agreedWith: String, val at: Long)

class TierLedger(private val family: FamilyGraph) {
    private class Slot { var child: SignedTier? = null; val guardians = LinkedHashMap<String, SignedTier>() }
    private val byDigest = LinkedHashMap<String, Slot>()

    fun add(e: NostrEvent): Boolean {
        val t = readTier(e) ?: return false
        val slot = byDigest.getOrPut(t.digest) { Slot() }
        if (t.signer == t.statement.child) { if (slot.child == null) slot.child = t }
        else slot.guardians.putIfAbsent(t.signer, t)
        return true
    }

    fun addAll(events: Iterable<NostrEvent>) { events.forEach { add(it) } }

    private fun complete(child: String): List<Pair<SignedTier, SignedTier>> = byDigest.values.mapNotNull { slot ->
        val c = slot.child?.takeIf { it.statement.child == child } ?: return@mapNotNull null
        val g = slot.guardians.values.filter { family.isGuardian(it.signer, child) }.minByOrNull { it.signer } ?: return@mapNotNull null
        c to g
    }

    private fun walk(child: String): List<Pair<SignedTier, SignedTier>> {
        val all = complete(child)
        val chain = ArrayList<Pair<SignedTier, SignedTier>>()
        var prev = TIER_GENESIS
        var seq = 0
        while (true) {
            val next = all.filter { it.first.statement.seq == seq && it.first.statement.prev == prev }.minByOrNull { it.first.digest } ?: break
            chain += next
            prev = next.first.digest
            seq++
        }
        return chain
    }

    fun current(child: String): TierPosition? = walk(child).lastOrNull()?.let { (c, g) ->
        TierPosition(c.statement.tier, c.statement.seq, c.digest, g.signer, maxOf(c.at, g.at))
    }

    fun pending(child: String): List<SignedTier> {
        val pos = current(child)
        val prev = pos?.digest ?: TIER_GENESIS
        val seq = pos?.seq?.plus(1) ?: 0
        return byDigest.values.mapNotNull { slot ->
            val any = slot.child ?: slot.guardians.values.firstOrNull() ?: return@mapNotNull null
            if (any.statement.child != child || any.statement.seq != seq || any.statement.prev != prev) return@mapNotNull null
            val guardianOk = slot.guardians.values.any { family.isGuardian(it.signer, child) }
            if (slot.child != null && guardianOk) null else any
        }
    }

    fun chainEvents(child: String): List<NostrEvent> = walk(child).flatMap { listOf(it.first.event, it.second.event) }
}

data class ApprovalPair(val approval: Approval, val child: SignedApproval, val guardian: SignedApproval)

class ApprovalBook(private val family: FamilyGraph) {
    private val halves = LinkedHashMap<String, LinkedHashMap<String, SignedApproval>>()

    fun add(e: NostrEvent): SignedApproval? {
        val a = readApproval(e) ?: return null
        halves.getOrPut(a.approval.key) { LinkedHashMap() }.putIfAbsent(a.signer, a)
        return a
    }

    fun addAll(events: Iterable<NostrEvent>) { events.forEach { add(it) } }

    fun pair(room: String, invitee: String, child: String, now: Long): ApprovalPair? = halves.values.mapNotNull { slot ->
        val a = slot.values.firstOrNull()?.approval ?: return@mapNotNull null
        if (a.room != room || a.invitee != invitee || a.child != child || a.expires <= now) return@mapNotNull null
        val c = slot[child] ?: return@mapNotNull null
        val g = slot.values.filter { it.signer != child && family.isGuardian(it.signer, child) }.minByOrNull { it.signer } ?: return@mapNotNull null
        ApprovalPair(a, c, g)
    }.maxByOrNull { maxOf(it.child.at, it.guardian.at) }

    fun halvesFor(room: String, invitee: String): List<SignedApproval> =
        halves.values.flatMap { it.values }.filter { it.approval.room == room && it.approval.invitee == invitee }
}
