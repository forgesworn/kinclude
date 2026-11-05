package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.deriveRoom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One person on one phone: their family, contacts, rooms and invitations.
 * The Kotlin twin of `src/household.ts`, and what the Android app drives.
 */
data class Contact(val pk: String, val name: String?, val kin: Boolean)
data class KnownChild(val pk: String, val name: String?, val inbox: String)
data class KnownGuardian(val pk: String, val name: String?)

data class PendingInvite(
    val id: String,
    val envelope: InvitationEnvelope,
    val sender: String,
    /** A child's phone: the guardian's half of the child's own approval. */
    val approvals: List<NostrEvent> = emptyList(),
    /** A guardian's phone: the invitation is for this child of theirs. */
    val forChild: String? = null,
)

data class PendingTier(val child: String, val tier: Tier, val seq: Int, val prev: String, val signedBy: String)

data class HouseholdData(
    val name: String,
    val child: Boolean,
    val phoneSecret: String,
    val family: List<NostrEvent> = emptyList(),
    val children: List<KnownChild> = emptyList(),
    val guardians: List<KnownGuardian> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val rooms: List<RoomSecrets> = emptyList(),
    val handled: List<String> = emptyList(),
    val invites: List<PendingInvite> = emptyList(),
)

interface HouseholdStore {
    suspend fun load(): HouseholdData?
    suspend fun save(data: HouseholdData)
}

class MemoryStore : HouseholdStore {
    @Volatile var data: HouseholdData? = null
    override suspend fun load() = data
    override suspend fun save(data: HouseholdData) { this.data = data }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Household private constructor(
    private val transport: RelayTransport,
    val identity: KincludeSigner,
    private val store: HouseholdStore,
    data: HouseholdData,
    private val now: () -> Long,
    private val onChange: (() -> Unit)?,
) {
    @Volatile var data: HouseholdData = data
        private set
    val rooms = java.util.concurrent.ConcurrentHashMap<String, KincludeRoom>()
    val family = FamilyGraph()
    val tiers = TierLedger(family)
    private val engine: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engine)
    private val subs = ArrayList<() -> Unit>()

    init { family.addAll(data.family); tiers.addAll(data.family) }

    companion object {
        suspend fun open(transport: RelayTransport, identity: KincludeSigner, store: HouseholdStore, name: String = "Me",
            child: Boolean = false, now: () -> Long = { System.currentTimeMillis() / 1000 }, onChange: (() -> Unit)? = null): Household {
            val data = store.load() ?: HouseholdData(sanitiseName(name) ?: "Me", child, Entropy.bytes(32).toHex())
            return Household(transport, identity, store, data, now, onChange).also { it.save() }
        }
    }

    val me: String get() = identity.pubkey
    private val phoneSecret get() = data.phoneSecret.hexToBytes()
    val inbox: String get() = pubkeyOf(inboxKey(phoneSecret))

    suspend fun start() = withContext(engine) {
        if (!data.child) subs += transport.subscribe(listOf(Filter.of(GIFT_WRAP_KIND, p = me)), { e -> scope.launch { onWrap(e, toIdentity = true) } })
        subs += transport.subscribe(listOf(Filter.of(GIFT_WRAP_KIND, p = inbox)), { e -> scope.launch { onWrap(e, toIdentity = false) } })
        for (s in data.rooms) openRoom(s)
    }

    fun close() { subs.forEach { it() }; rooms.values.forEach { it.close() }; scope.cancel() }

    private fun update(f: (HouseholdData) -> HouseholdData) { data = f(data); changed() }

    private fun changed() { scope.launch { save() }; onChange?.invoke() }

    suspend fun save() {
        val current = data
        val withEpochs = current.copy(rooms = current.rooms.map { s -> rooms[deriveRoom(s.secret.hexToBytes()).roomId]?.secrets() ?: s })
        store.save(withEpochs)
    }

    // ------------------------------------------------------------ family

    fun isMyChild(pk: String?) = pk != null && data.children.any { it.pk == pk }

    /** Keep the links and tier statements that bear on this person or their children. */
    fun addStatements(events: List<NostrEvent>): Boolean {
        var added = false
        val known = data.family.map { it.id }.toHashSet()
        val keep = ArrayList<NostrEvent>()
        for (e in events) {
            if (e.id in known) continue
            val ours = { pk: String -> pk == me || isMyChild(pk) }
            val link = readLinkStatement(e)
            val tier = readTier(e)
            val relevant = when {
                link != null -> ours(link.signer) || ours(link.other)
                tier != null -> ours(tier.statement.child)
                else -> false
            }
            if (!relevant) continue
            keep += e; known += e.id; family.add(e); tiers.add(e); added = true
        }
        if (added) update { it.copy(family = it.family + keep) }
        return added
    }

    fun statementsFor(child: String): List<NostrEvent> = family.statementsFor(child) + tiers.chainEvents(child)
    fun tierOf(child: String): Tier? = tiers.current(child)?.tier

    fun offerPairing(tier: Tier, childName: String? = null): PairingOffer {
        val offer = offerPairing(transport, identity, tier, now, data.name, childName, scope)
        scope.launch {
            val child = offer.paired.await()
            update { d -> d.copy(children = d.children.filter { it.pk != child.pk } + KnownChild(child.pk, child.name, child.inbox)) }
            addStatements(child.events)
        }
        return offer
    }

    suspend fun acceptPairing(code: String): String {
        val res = acceptPairing(transport, identity, inboxKey(phoneSecret), code, now, data.name, data.family)
        update { d -> d.copy(guardians = d.guardians.filter { it.pk != res.guardian } + KnownGuardian(res.guardian, res.guardianName)) }
        addStatements(res.events)
        tellGuardians()
        for (room in rooms.values) room.postStatements(statementsFor(me))
        return res.guardian
    }

    // ------------------------------------------------------------ tiers

    suspend fun proposeTier(child: String, tier: Tier) {
        require(child == me || family.isGuardian(me, child)) { "only the child or their guardian agrees a tier" }
        val pos = tiers.current(child)
        if (pos?.tier == tier) return
        val s = TierStatement(child, tier, pos?.seq?.plus(1) ?: 0, pos?.digest ?: TIER_GENESIS)
        val e = identity.sign(tierUnsigned(s, now(), nameOf(child)))
        addStatements(listOf(e))
        carryTier(child, listOf(e))
    }

    private fun nameOf(child: String) = if (child == me) data.name else data.children.firstOrNull { it.pk == child }?.name

    fun pendingTiers(): List<PendingTier> {
        val signed = data.family.filter { it.pubkey == me }.mapNotNull { readTier(it)?.digest }.toSet()
        val children = if (data.child) listOf(me) else data.children.map { it.pk }
        return children.flatMap { child ->
            tiers.pending(child).filter { it.signer != me && it.digest !in signed }
                .map { PendingTier(child, it.statement.tier, it.statement.seq, it.statement.prev, it.signer) }
        }
    }

    suspend fun agreeTier(p: PendingTier) {
        val e = identity.sign(tierUnsigned(TierStatement(p.child, p.tier, p.seq, p.prev), now(), nameOf(p.child)))
        addStatements(listOf(e))
        carryTier(p.child, listOf(e))
    }

    private suspend fun carryTier(child: String, fresh: List<NostrEvent>) {
        val chain = tiers.chainEvents(child)
        for (room in rooms.values) {
            val s = room.view().state ?: continue
            if (s.member(child) == null) continue
            val have = room.tiers.current(child)?.digest
            val want = tiers.current(child)?.digest
            val events = ((if (have != want) chain else emptyList()) + fresh).distinctBy { it.id }
            if (events.isNotEmpty()) room.postStatements(events, "Oversight for a child here changed.")
        }
    }

    private fun onRoomStatements(events: List<NostrEvent>) {
        if (!addStatements(events)) return
        val children = if (data.child) listOf(me) else data.children.map { it.pk }
        scope.launch { children.forEach { carryTier(it, emptyList()) } }
    }

    // ------------------------------------------------------------ contacts and invitations

    fun setContacts(contacts: List<Contact>) = update { it.copy(contacts = contacts.filter { c -> isPubkey(c.pk) }) }

    private fun trusted(pk: String) = pk == me || data.contacts.any { it.pk == pk } || data.guardians.any { it.pk == pk } || isMyChild(pk)

    private suspend fun onWrap(e: NostrEvent, toIdentity: Boolean) {
        if (e.id in data.handled) return
        update { it.copy(handled = it.handled + e.id) }
        if (toIdentity) {
            val (rumor, sender) = unwrapGift(e, identity) ?: return
            when (rumor.kind) {
                FAMILY_RUMOR_KIND -> if (data.children.any { it.inbox == sender }) runCatching {
                    addStatements(Json.parseToJsonElement(rumor.content).jsonArray.take(64).map { NostrEvent.fromJson(it) })
                }
                INVITATION_RUMOR_KIND -> {
                    if (!trusted(sender)) return
                    val env = runCatching { InvitationEnvelope.read(Json.parseToJsonElement(rumor.content).jsonObject) }.getOrNull() ?: return
                    if (env.role == Role.ADULT && env.invitee == me && !rooms.containsKey(env.room)) update { it.copy(invites = it.invites + PendingInvite(e.id, env, sender)) }
                    else if (env.role == Role.CHILD && isMyChild(env.invitee) && me in env.guardians)
                        update { it.copy(invites = it.invites + PendingInvite(e.id, env, sender, forChild = env.invitee)) }
                }
            }
        } else {
            val (rumor, sender) = unwrapGift(e, LocalSigner(inboxKey(phoneSecret))) ?: return
            if (rumor.kind != FORWARD_RUMOR_KIND || data.guardians.none { it.pk == sender }) return
            runCatching {
                val body = Json.parseToJsonElement(rumor.content).jsonObject
                val env = InvitationEnvelope.read(body.getValue("envelope").jsonObject) ?: return
                val approvals = body["approvals"]?.jsonArray?.map { NostrEvent.fromJson(it) } ?: emptyList()
                if (env.invitee == me && !rooms.containsKey(env.room) && data.invites.none { it.envelope.room == env.room })
                    update { it.copy(invites = it.invites + PendingInvite(e.id, env, sender, approvals)) }
            }
        }
    }

    private suspend fun tellGuardians() {
        val inbox = LocalSigner(inboxKey(phoneSecret))
        val content = JsonArray(statementsFor(me).map { it.toJson() }).toString()
        for (g in data.guardians) transport.publish(wrapGift(inbox, g.pk, FAMILY_RUMOR_KIND, content, now()))
    }

    fun declineInvite(id: String) = update { it.copy(invites = it.invites.filter { i -> i.id != id }) }

    suspend fun acceptInvite(id: String): KincludeRoom? {
        val invite = data.invites.firstOrNull { it.id == id } ?: error("no such invitation")
        update { it.copy(invites = it.invites.filter { i -> i.id != id }) }
        val env = invite.envelope
        invite.forChild?.let { childPk ->
            val child = data.children.first { it.pk == childPk }
            val terms = Approval(env.room, child.pk, child.pk, Role.CHILD, env.guardians, now() + APPROVAL_TTL_SECONDS)
            val half = identity.sign(approvalUnsigned(terms, now(), child.name))
            val body = buildJsonObject { put("envelope", env.toJson()); put("approvals", JsonArray(listOf(half.toJson()))) }
            transport.publish(wrapGift(identity, child.inbox, FORWARD_RUMOR_KIND, body.toString(), now()))
            return rooms[env.room] ?: join(env, statementsFor(child.pk))
        }
        if (data.child) {
            val theirs = invite.approvals.mapNotNull(::readApproval).firstOrNull { it.approval.invitee == me && it.approval.child == me }
                ?: error("this invitation came without your guardian's approval")
            val mine = identity.sign(approvalUnsigned(theirs.approval, now(), data.name))
            return join(env, statementsFor(me) + theirs.event + mine)
        }
        return join(env, emptyList())
    }

    private suspend fun join(env: InvitationEnvelope, hello: List<NostrEvent>): KincludeRoom {
        val secret = requestPersistentAdmission(transport, RoomInvitation(env.bearer.hexToBytes(), env.authority, true))
        check(deriveRoom(secret).roomId == env.room) { "the invitation opened a different room" }
        val secrets = RoomSecrets(secret.toHex(), env.authority)
        update { it.copy(rooms = it.rooms + secrets) }
        return openRoom(secrets, hello)
    }

    private suspend fun openRoom(secrets: RoomSecrets, family: List<NostrEvent> = emptyList()): KincludeRoom {
        val room = KincludeRoom(RoomOptions(transport, identity, data.name, secrets, phoneSecret,
            family.ifEmpty { if (data.child) statementsFor(me) else emptyList() }, now, onChange = { changed() }, onStatements = ::onRoomStatements))
        rooms[room.roomId] = room
        room.start()
        return room
    }

    suspend fun createRoom(name: String): KincludeRoom {
        val room = KincludeRoom.create(RoomOptions(transport, identity, data.name, RoomSecrets("", ""), phoneSecret, now = now,
            onChange = { changed() }, onStatements = ::onRoomStatements), name)
        rooms[room.roomId] = room
        update { it.copy(rooms = it.rooms + room.secrets()) }
        return room
    }

    suspend fun addChild(room: KincludeRoom, child: String) {
        require(family.isGuardian(me, child)) { "not your child" }
        val name = data.children.firstOrNull { it.pk == child }?.name
        room.postStatements(family.statementsFor(child), "${name ?: "A child"}'s family.")
        room.propose(child, Role.CHILD, name, family.guardiansOf(child))
    }
}
