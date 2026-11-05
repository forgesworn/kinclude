package app.kinclude.core

/**
 * The rules, as pure functions of a room's state: the Kotlin twin of
 * `src/policy.ts` (protocol §5). The authority's phone applies them before
 * changing the room, and every other phone applies them again to each
 * change it is shown.
 */
fun familyOf(state: RoomState, extra: List<StateMember> = emptyList()): FamilyGraph =
    FamilyGraph().also { g -> (state.members + extra).forEach { g.addAll(it.links) } }

fun requiredChildren(state: RoomState, invitee: String, role: Role): List<String> {
    val out = state.members.filter { it.role == MemberRole.CHILD && it.pk != invitee }.map { it.pk }.toMutableList()
    if (role == Role.CHILD) out += invitee
    return out.distinct().sorted()
}

sealed interface Verdict {
    data class Ok(val pairs: List<ApprovalPair>) : Verdict
    data class No(val reason: String, val missing: List<String> = emptyList()) : Verdict
}

private fun MemberRole.asRole(): Role? = when (this) { MemberRole.ADULT -> Role.ADULT; MemberRole.CHILD -> Role.CHILD; else -> null }

fun checkNewcomer(state: RoomState, entry: StateMember, now: Long): Verdict {
    val role = entry.role.asRole() ?: return Verdict.No("a guardian joins through their child, not by approval")
    val family = familyOf(state, listOf(entry))
    if (role == Role.CHILD && family.guardiansOf(entry.pk).isEmpty()) return Verdict.No("a child joins with their guardian links")
    if (role == Role.ADULT && family.isChild(entry.pk)) return Verdict.No("somebody with a guardian joins as a child")
    val book = ApprovalBook(family).also { it.addAll(entry.approvals) }
    val guardians = if (role == Role.CHILD) family.guardiansOf(entry.pk) else emptyList()
    val pairs = ArrayList<ApprovalPair>()
    val missing = ArrayList<String>()
    for (child in requiredChildren(state, entry.pk, role)) {
        val pair = book.pair(state.room, entry.pk, child, now)
        if (pair == null || pair.approval.role != role || pair.approval.guardians.sorted() != guardians) missing += child else pairs += pair
    }
    return if (missing.isEmpty()) Verdict.Ok(pairs) else Verdict.No("approvals missing", missing)
}

/** A child's own approval pair, judged at the child's arrival. */
private fun ownPair(state: RoomState, child: StateMember, family: FamilyGraph): ApprovalPair? =
    ApprovalBook(family).also { it.addAll(child.approvals) }.pair(state.room, child.pk, child.pk, child.at)

fun mayObserve(state: RoomState, pk: String): Boolean {
    val family = familyOf(state)
    return state.children.any { c -> family.isGuardian(pk, c.pk) && ownPair(state, c, family)?.approval?.guardians?.contains(pk) == true }
}

fun interface TierOf { fun tier(child: String): Tier? }

fun computeReaders(state: RoomState, tierOf: TierOf): List<String> {
    val family = familyOf(state)
    val out = sortedSetOf<String>()
    state.members.filter { it.role != MemberRole.GUARDIAN }.forEach { out += it.pk }
    state.members.filter { it.role == MemberRole.GUARDIAN }.forEach { g ->
        val reading = state.children.any { c -> family.isGuardian(g.pk, c.pk) && (tierOf.tier(c.pk) ?: Tier.FIRST_MESSAGES).n < 3 }
        if (reading) out += g.pk
    }
    return out.toList()
}

data class ChildBanner(
    val child: String,
    val tier: Tier?,
    val reading: List<String>,
    val notReading: List<String>,
    val inOwnRight: List<String>,
    val catchingUp: Boolean,
)

fun banners(state: RoomState, tierOf: TierOf): List<ChildBanner> {
    val family = familyOf(state)
    val readers = state.readers.toSet()
    val expected = computeReaders(state, tierOf).toSet()
    return state.children.map { c ->
        val guardians = family.guardiansOf(c.pk)
        val inOwnRight = guardians.filter { state.member(it)?.role == MemberRole.ADULT }
        val listed = guardians.filter { state.member(it)?.role == MemberRole.GUARDIAN }
        ChildBanner(
            c.pk, tierOf.tier(c.pk),
            listed.filter { it in readers }, listed.filter { it !in readers }, inOwnRight,
            listed.any { (it in readers) != (it in expected) },
        )
    }
}

fun mayRemove(state: RoomState, actor: String, target: String, creator: String): Boolean {
    val t = state.member(target) ?: return false
    if (state.member(actor) == null) return false
    if (target == creator && actor != creator) return false
    if (actor == target || actor == creator || t.by == actor) return true
    val family = familyOf(state)
    return state.children.any { family.isGuardian(actor, it.pk) }
}

sealed interface Transition {
    data object Ok : Transition
    data class No(val reason: String) : Transition
}

fun checkTransition(prev: RoomState?, next: RoomState, at: Long, creator: String): Transition {
    if (prev == null) {
        if (next.seq != 0 || next.prev != null) return Transition.No("a room starts at seq 0")
        val only = next.members.singleOrNull()
        if (only == null || only.pk != creator || only.role != MemberRole.ADULT) return Transition.No("a room starts with its creator alone")
        return Transition.Ok
    }
    if (next.room != prev.room || next.seq != prev.seq + 1) return Transition.No("out of sequence")
    if (next.epoch < prev.epoch) return Transition.No("the epoch went backwards")
    if (next.member(creator) == null) return Transition.No("the creator is always in their room")
    var running = prev.copy(members = prev.members.filter { next.member(it.pk) != null })
    val guardiansAdded = ArrayList<StateMember>()
    for (m in next.members) {
        val before = prev.member(m.pk)
        if (before != null && before.role == m.role) continue
        if (m.role == MemberRole.GUARDIAN) { guardiansAdded += m; continue }
        val verdict = checkNewcomer(running, m, at)
        if (verdict is Verdict.No) return Transition.No("${m.pk.take(8)}: ${verdict.reason}")
        running = running.copy(members = running.members.filter { it.pk != m.pk } + m)
    }
    for (g in guardiansAdded) if (!mayObserve(running, g.pk)) return Transition.No("${g.pk.take(8)} is not a guardian the room approved")
    for (m in next.members) if (m.role == MemberRole.GUARDIAN && !mayObserve(next, m.pk)) return Transition.No("${m.pk.take(8)} has no child here")
    return Transition.Ok
}
