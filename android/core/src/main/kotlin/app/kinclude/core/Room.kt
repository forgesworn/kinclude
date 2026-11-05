package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.EpochGrant
import dev.forgesworn.kithmoot.protocol.KIND_DEVICE_CREDENTIAL
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_REQUEST
import dev.forgesworn.kithmoot.protocol.KIND_GROUP_INVITATION
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_RETIREMENT
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.RoomInvitationHost
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeEpochGrant
import dev.forgesworn.kithmoot.protocol.decodeEpochRequest
import dev.forgesworn.kithmoot.protocol.decodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.decodePersistentInvitation
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveInvitationId
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeEpochRequest
import dev.forgesworn.kithmoot.protocol.encodeInvitationRetirement
import dev.forgesworn.kithmoot.protocol.encodePersistentInvitation
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.peekRekeyEpoch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * One device's view of, and part in, one Kinclude room: the Kotlin twin of
 * `src/room.ts`, on KithMoot's Kotlin protocol. Protocol §3–§5.
 *
 * Everything the room does runs on [engine], a dispatcher one coroutine
 * wide, so its state is touched by one thing at a time, as in the
 * TypeScript event loop.
 */
const val CREDENTIAL_SECONDS = 365L * 24 * 60 * 60
const val CALL_RECORD_HOURS = 6L

data class RoomSecrets(
    val secret: String,
    val authority: String,
    val authoritySk: String? = null,
    val epochs: Map<Int, String> = emptyMap(),
    val issued: Map<String, String> = emptyMap(),
)

data class ShownMessage(val id: String, val from: String, val name: String?, val text: String, val sentAt: Long, val epoch: Int, val viaEmergency: Boolean)
data class ProposalApproval(val child: String, val byChild: Boolean, val byGuardian: String?)
data class Proposal(val invitee: String, val name: String?, val role: Role, val guardians: List<String>, val by: String, val at: Long,
    val expires: Long, val approvals: List<ProposalApproval>, val invited: Boolean)
data class Receipt(val guardian: String, val child: String, val upTo: String, val at: Long)
data class CallRecord(val participant: String, val call: String, val join: Boolean, val video: Boolean, val at: Long)
data class CallNow(val id: String, val on: List<CallRecord>)

data class RoomView(
    val roomId: String,
    val name: String,
    val state: RoomState?,
    val stateProblem: String?,
    val creator: String?,
    val me: String,
    val role: MemberRole?,
    val epoch: Int,
    val reading: Boolean,
    val removed: Boolean,
    val banners: List<ChildBanner>,
    val tierOf: Map<String, Tier?>,
    val messages: List<ShownMessage>,
    val unrevealed: Int,
    val gatedFor: List<String>,
    val proposals: List<Proposal>,
    val receipts: List<Receipt>,
    val emergencies: List<SignedEmergency>,
    val openable: List<String>,
    val call: CallNow?,
    val callLog: List<CallRecord>,
)

/** What a call needs from its room (`CallContext` in the TypeScript). */
class CallContext(
    val roomId: String, val me: String, val device: String, val deviceSk: ByteArray, val credential: NostrEvent,
    val transport: RelayTransport, val now: () -> Long, val epoch: Pair<Int, ByteArray>?, val readers: List<String>,
)

class RoomOptions(
    val transport: RelayTransport,
    val identity: KincludeSigner,
    val name: String? = null,
    val secrets: RoomSecrets,
    val phoneSecret: ByteArray,
    val family: List<NostrEvent> = emptyList(),
    val now: () -> Long = { System.currentTimeMillis() / 1000 },
    val onChange: (() -> Unit)? = null,
    val onStatements: ((List<NostrEvent>) -> Unit)? = null,
    val requestEveryMs: Long = 5_000,
    val approvalSeconds: Long = APPROVAL_TTL_SECONDS,
)

/** A participant's room credential, signed by their identity (My Signet). */
suspend fun createRoomCredential(identity: KincludeSigner, device: String, roomId: String, expiresAt: Long, now: Long): NostrEvent =
    identity.signEvent(KIND_DEVICE_CREDENTIAL, now, listOf(listOf("d", roomId), listOf("device", device), listOf("expiration", expiresAt.toString())), "")

/** Join from stored admission alone: the group invitation and any
 *  retirement in one query, waiting for end-of-stored-events. */
suspend fun requestPersistentAdmission(transport: RelayTransport, invitation: RoomInvitation, timeoutMs: Long = 10_000): ByteArray {
    val id = deriveInvitationId(invitation)
    val found = ArrayList<NostrEvent>()
    val done = CompletableDeferred<Unit>()
    val stop = transport.subscribe(listOf(Filter(listOf(KIND_GROUP_INVITATION, KIND_INVITATION_RETIREMENT), mapOf("d" to listOf(id)))),
        { synchronized(found) { found += it } }, { done.complete(Unit) })
    try { withTimeout(timeoutMs) { done.await() } } finally { stop() }
    val events = synchronized(found) { found.toList() }
    if (events.any { decodeInvitationRetirement(it, invitation) }) throw IllegalStateException("This invitation has been withdrawn.")
    return events.firstNotNullOfOrNull { decodePersistentInvitation(it, invitation)?.secret } ?: throw IllegalStateException("The invitation is not available.")
}

@OptIn(ExperimentalCoroutinesApi::class)
class KincludeRoom(private val opts: RoomOptions) {
    val roomId: String
    val roomKey: ByteArray
    val authority: String = opts.secrets.authority
    val device: String
    val lobby: Stream
    val family = FamilyGraph()
    val tiers = TierLedger(family)
    val book = ApprovalBook(family)

    private val engine: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + engine)
    private val secret = opts.secrets.secret.hexToBytes()
    private val authoritySk = opts.secrets.authoritySk?.hexToBytes()
    private val deviceSk: ByteArray
    private var credential: NostrEvent? = null
    private val epochs = sortedMapOf<Int, ByteArray>()
    private var current = 0
    private val issued = LinkedHashMap(opts.secrets.issued)

    private val seen = HashSet<String>()
    private val rekeys = HashMap<Int, MutableList<NostrEvent>>()
    private val states = ArrayList<NostrEvent>()
    private val accepted = ArrayList<SignedState>()
    private var stateProblem: String? = null
    private var creator: String? = null
    private class Held(var msg: RoomMessage, val epoch: Int, val viaEmergency: Boolean)
    private val messages = LinkedHashMap<String, Held>()
    private class Proposed(val p: Payload.Propose, val by: String, val at: Long)
    private val proposals = LinkedHashMap<String, Proposed>()
    private class HeldReceipt(val r: Receipt, val epoch: Int, val participant: String, val sentAt: Long)
    private val receipts = ArrayList<HeldReceipt>()
    private class HeldEscrow(val device: String, val copies: List<Pair<String, String>>)
    private val escrows = HashMap<String, HeldEscrow>()
    private val emergencies = ArrayList<SignedEmergency>()
    private val hellos = LinkedHashMap<String, Pair<Long, String?>>()
    private val removals = ArrayList<Pair<String, String>>()
    private class HeldCall(val r: CallRecord, val epoch: Int)
    private val callRecords = ArrayList<HeldCall>()
    private val devices = HashMap<String, LinkedHashMap<String, NostrEvent?>>()
    private val requests = LinkedHashSet<String>()
    private var lastRequest = 0L
    private val escrowed = HashSet<Int>()
    private val rekeyAfterEmergency = HashSet<String>()
    private val opened = sortedMapOf<Int, ByteArray>()
    private val revealed = HashSet<String>()
    private var removedMe = false
    private val subs = ArrayList<() -> Unit>()
    private val mainSubs = HashMap<Int, () -> Unit>()
    private val raw = HashMap<String, MutableList<NostrEvent>>()
    private var settleQueued = false
    private val ready = CompletableDeferred<Unit>()
    private var closed = false
    private val listeners = LinkedHashSet<() -> Unit>()

    init {
        val room = deriveRoom(secret)
        roomId = room.roomId
        roomKey = room.roomKey
        if (authoritySk != null) require(Schnorr.publicKeyHex(authoritySk) == authority) { "authority key does not match" }
        deviceSk = roomDeviceKey(opts.phoneSecret, roomId)
        device = Schnorr.publicKeyHex(deviceSk)
        lobby = deriveChannel(roomId, roomKey, LOBBY_CHANNEL)
        epochs[0] = secret
        for ((n, s) in opts.secrets.epochs) if (n > 0) epochs[n] = s.hexToBytes()
        current = epochs.lastKey()
        family.addAll(opts.family)
        tiers.addAll(opts.family)
    }

    val me: String get() = opts.identity.pubkey
    private fun now() = opts.now()
    private val tierOf = TierOf { tiers.current(it)?.tier }

    companion object {
        /** Make a new room: this device holds its authority, and its creator is
         *  its only member until somebody is approved in. */
        suspend fun create(opts: RoomOptions, roomName: String): KincludeRoom {
            val host = createRoomInvitation(true)
            val room = KincludeRoom(RoomOptions(
                opts.transport, opts.identity, opts.name,
                RoomSecrets(Entropy.bytes(32).toHex(), host.invitation.canonicalInviter, host.inviterSecretKey.toHex()),
                opts.phoneSecret, opts.family, opts.now, opts.onChange, opts.onStatements, opts.requestEveryMs, opts.approvalSeconds,
            ))
            room.start()
            room.found(roomName)
            return room
        }
    }

    fun secrets(): RoomSecrets = RoomSecrets(
        secret.toHex(), authority, authoritySk?.toHex(),
        epochs.filterKeys { it > 0 }.mapValues { it.value.toHex() }, LinkedHashMap(issued),
    )

    fun subscribe(listener: () -> Unit): () -> Unit {
        synchronized(listeners) { listeners += listener }
        return { synchronized(listeners) { listeners -= listener } }
    }

    private fun deliver(e: NostrEvent) { scope.launch { onEvent(e) } }

    suspend fun start() = withContext(engine) {
        if (credential == null) credential = createRoomCredential(opts.identity, device, roomId, now() + CREDENTIAL_SECONDS, now())
        noteDevice(me, device, credential)
        val t = opts.transport
        subs += t.subscribe(listOf(Filter.of(KIND_CHAT, d = lobby.id)), ::deliver) { scope.launch { ready.complete(Unit) } }
        subs += t.subscribe(listOf(Filter.of(KIND_ROOM_REKEY, d = roomId)), ::deliver)
        subs += t.subscribe(listOf(Filter.of(KIND_EPOCH_GRANT, d = roomId, p = device)), ::deliver)
        if (authoritySk != null) subs += t.subscribe(listOf(Filter.of(KIND_EPOCH_REQUEST, d = roomId, p = authority)), ::deliver)
        for (n in epochs.keys.toList()) if (n > 0) watchEpoch(n)
        ready.await()
        val head = head()
        if (authoritySk == null && (head == null || head.state.member(me) == null)) hello()
        queueSettle()
    }

    suspend fun hello(extra: List<NostrEvent> = emptyList()) = withContext(engine) {
        val events = opts.family + extra
        family.addAll(events); tiers.addAll(events); book.addAll(events)
        post(Payload.Hello(events), lobby = true, "${opts.name ?: "Someone"} arrived.")
    }

    fun close() {
        closed = true
        subs.forEach { it() }
        mainSubs.values.forEach { it() }
        mainSubs.clear()
        scope.cancel()
    }

    private suspend fun found(roomName: String) = withContext(engine) {
        val sk = requireNotNull(authoritySk) { "only the authority founds a room" }
        if (accepted.isNotEmpty()) return@withContext
        val next = Entropy.bytes(32)
        opts.transport.publish(encodeRekeyEvent(roomId, sk, deriveEpoch(RoomEpoch(0, secret)), RoomEpoch(1, next),
            listOf(device), emptyList(), now(), commit = true, members = listOf(me)))
        adoptEpoch(1, next)
        publishState(RoomState(roomId, 0, null, roomName, 1,
            listOf(StateMember(me, MemberRole.ADULT, opts.name, me, now())), listOf(me), emptyList()))
    }

    // ------------------------------------------------------------ intake

    private fun onEvent(e: NostrEvent) {
        if (closed || !seen.add(e.id)) return
        when (e.kind) {
            KIND_CHAT -> onChat(e)
            KIND_ROOM_REKEY -> {
                val epoch = peekRekeyEpoch(e, roomId, authority) ?: return
                rekeys.getOrPut(epoch) { ArrayList() } += e
                advance()
            }
            KIND_EPOCH_GRANT -> onGrant(e)
            KIND_EPOCH_REQUEST -> onRequest(e)
        }
        queueSettle()
    }

    private fun onChat(e: NostrEvent) {
        val d = e.tagValue("d")
        if (d == lobby.id) {
            decodeMessage(e, roomId, lobby, now())?.let { onMessage(it, 0, lobby = true) }
            return
        }
        for ((n, s) in epochs + opened) {
            val keys = deriveEpoch(RoomEpoch(n, s))
            if (keys.id != d) continue
            decodeMessage(e, roomId, Stream(keys.id, keys.key), now())?.let { onMessage(it, n, lobby = false, viaEmergency = n in opened && n !in epochs) }
            return
        }
        raw.getOrPut(d ?: "") { ArrayList() } += e
    }

    private fun onMessage(msg: RoomMessage, epoch: Int, lobby: Boolean, viaEmergency: Boolean = false) {
        noteDevice(msg.participant, msg.device, msg.credential)
        when (val k = msg.kinclude) {
            null -> if (!lobby) messages[msg.id] = Held(msg, epoch, viaEmergency)
            is Payload.Statements, is Payload.Hello -> {
                val events = if (k is Payload.Statements) k.events else (k as Payload.Hello).events
                family.addAll(events); tiers.addAll(events); book.addAll(events)
                if (events.isNotEmpty()) opts.onStatements?.invoke(events)
                if (k is Payload.Hello && lobby) {
                    val held = hellos[msg.participant]
                    if (held == null || held.first <= msg.sentAt) hellos[msg.participant] = msg.sentAt to msg.name
                }
            }
            is Payload.Propose -> if (!lobby) {
                val held = proposals[k.invitee]
                if (held == null || held.at <= msg.sentAt) proposals[k.invitee] = Proposed(k, msg.participant, msg.sentAt)
            }
            is Payload.State -> { if (lobby) states += k.event; acceptStates() }
            is Payload.Read -> if (!lobby) receipts += HeldReceipt(Receipt(msg.participant, k.child, k.upTo, k.at), epoch, msg.participant, msg.sentAt)
            is Payload.Escrow -> if (lobby) escrows["${msg.participant}:${k.epoch}"] = HeldEscrow(msg.device, k.copies)
            is Payload.Emergency -> if (lobby) {
                val e = readEmergency(k.event)
                if (e != null && e.signer == msg.participant && e.notice.room == roomId && family.isGuardian(e.signer, e.notice.child) &&
                    emergencies.none { it.event.id == e.event.id }) {
                    emergencies += e
                    if (e.notice.epoch == head()?.state?.epoch) rekeyAfterEmergency += e.event.id
                }
            }
            is Payload.Remove -> if (!lobby) removals += msg.participant to k.target
            is Payload.Call -> if (!lobby) callRecords += HeldCall(CallRecord(msg.participant, k.call, k.join, k.video, msg.sentAt), epoch)
        }
        if (!lobby && msg.retracts != null) {
            val target = messages[msg.retracts]
            if (target != null && target.msg.participant == msg.participant) target.msg = target.msg.copy(text = "Message withdrawn")
        }
    }

    private fun noteDevice(participant: String, device: String, credential: NostrEvent?) {
        val map = devices.getOrPut(participant) { LinkedHashMap() }
        if (credential != null || device !in map) map[device] = credential ?: map[device]
    }

    // ------------------------------------------------------------ state

    private fun acceptStates() {
        var moved = true
        while (moved) {
            moved = false
            val head = accepted.lastOrNull()
            for (ev in states) {
                val signed = readState(ev, roomId, authority) ?: continue
                val s = signed.state
                if (head == null) {
                    if (s.seq != 0) continue
                    val c = s.members.firstOrNull()?.pk ?: continue
                    val check = checkTransition(null, s, ev.createdAt, c)
                    if (check is Transition.No) { stateProblem = check.reason; continue }
                    creator = c
                } else {
                    if (s.seq != head.state.seq + 1 || s.prev != head.event.id) continue
                    val check = checkTransition(head.state, s, ev.createdAt, creator!!)
                    if (check is Transition.No) { stateProblem = check.reason; continue }
                }
                accepted += signed
                stateProblem = null
                for (m in s.members) { family.addAll(m.links); book.addAll(m.approvals) }
                moved = true
                break
            }
        }
    }

    private fun head(): SignedState? = accepted.lastOrNull()

    // ------------------------------------------------------------ epochs

    private fun adoptEpoch(n: Int, s: ByteArray) {
        if (n in epochs) return
        epochs[n] = s
        if (n > current) current = n
        watchEpoch(n)
        advance()
    }

    private fun watchEpoch(n: Int) {
        if (n in mainSubs) return
        val s = epochs[n] ?: opened[n] ?: return
        val id = deriveEpoch(RoomEpoch(n, s)).id
        raw.remove(id)?.forEach { seen.remove(it.id); onEvent(it) }
        mainSubs[n] = opts.transport.subscribe(listOf(Filter.of(KIND_CHAT, d = id)), ::deliver)
    }

    private fun advance() {
        while (true) {
            val next = rekeys[current + 1] ?: return
            val keys = deriveEpoch(RoomEpoch(current, epochs.getValue(current)))
            var moved = false
            for (e in next) {
                val notice = decodeRekeyEvent(e, roomId, authority, keys, deviceSk)
                if (notice?.removed?.contains(me) == true) removedMe = true
                val s = notice?.secret
                if (s != null) { adoptEpoch(notice.epoch, s); moved = true; break }
            }
            if (!moved) return
        }
    }

    private fun onGrant(e: NostrEvent) {
        for (request in requests.toList()) {
            val grant = decodeEpochGrant(e, roomId, authority, deviceSk, request, now()) ?: continue
            requests.remove(request)
            when (grant) {
                is EpochGrant.Refused -> if (grant.reason == "removed") removedMe = true
                is EpochGrant.Current -> grant.secret?.let { removedMe = false; adoptEpoch(grant.epoch, it) }
            }
            return
        }
    }

    private suspend fun requestEpoch() {
        val cred = credential ?: return
        val t = System.currentTimeMillis()
        if (t - lastRequest < opts.requestEveryMs) return
        lastRequest = t
        val event = encodeEpochRequest(roomId, authority, roomKey, deviceSk, cred, now())
        requests += event.id
        opts.transport.publish(event)
    }

    // ------------------------------------------------------------ the desk

    private fun onRequest(e: NostrEvent) {
        val sk = authoritySk ?: return
        val req = decodeEpochRequest(e, roomId, sk, roomKey, now()) ?: return
        noteDevice(req.participant, req.device, null)
        val s = head()?.state ?: return
        val epochSecret = epochs[s.epoch]
        val grant = when {
            req.participant in s.readers && epochSecret != null ->
                encodeEpochGrant(roomId, sk, req.device, req.request, now(), RoomEpoch(s.epoch, epochSecret), s.removed)
            req.participant in s.removed -> encodeEpochGrant(roomId, sk, req.device, req.request, now(), refused = "removed")
            // Anybody else may be waiting to be admitted: say nothing yet.
            else -> null
        }
        if (grant != null) scope.launch { opts.transport.publish(grant) }
    }

    // ------------------------------------------------------------ settling

    private fun queueSettle() {
        if (settleQueued || closed) return
        settleQueued = true
        scope.launch {
            settleQueued = false
            try { settle() } catch (t: Throwable) { if (t is kotlinx.coroutines.CancellationException) throw t; System.err.println("kinclude: settle failed: $t") }
            opts.onChange?.invoke()
            synchronized(listeners) { listeners.toList() }.forEach { it() }
        }
    }

    /** Resolves once the engine has run what it already holds. */
    suspend fun idle() { withContext(engine) {} }

    private suspend fun settle() {
        val s = head()?.state ?: return
        if (me in s.readers && current < s.epoch) requestEpoch()
        escrowDuty(s)
        if (authoritySk != null) authorityDuty()
    }

    private suspend fun escrowDuty(s: RoomState) {
        if (s.member(me)?.role != MemberRole.CHILD || tierOf.tier(me) != Tier.NEARLY_GROWN) return
        if (current != s.epoch || s.epoch in escrowed) return
        val epochSecret = epochs[s.epoch] ?: return
        val guardians = family.guardiansOf(me).filter { s.member(it)?.role == MemberRole.GUARDIAN && it !in s.readers }
        if (guardians.isEmpty()) return
        escrowed += s.epoch
        val copies = guardians.map { g -> g to Nip44.encrypt(epochSecret.toHex(), Nip44.conversationKey(deviceSk, g.hexToBytes())) }
        post(Payload.Escrow(s.epoch, copies), lobby = true, "Kept a sealed key for an emergency.")
    }

    private suspend fun authorityDuty() {
        val sk = authoritySk!!
        val head = head()!!
        val s = head.state
        val now = now()
        var members = s.members.toMutableList()
        var changed = false

        for ((actor, target) in removals.toList().also { removals.clear() }) {
            if (members.none { it.pk == target } || target == creator) continue
            if (!mayRemove(s.copy(members = members), actor, target, creator!!)) continue
            members = members.filter { it.pk != target }.toMutableList()
            changed = true
            issued.remove(target)?.let { bearer ->
                opts.transport.publish(encodeInvitationRetirement(RoomInvitation(bearer.hexToBytes(), authority, true), sk, now))
            }
        }
        while (true) {
            val draft = s.copy(members = members)
            val orphan = members.firstOrNull { it.role == MemberRole.GUARDIAN && !mayObserve(draft, it.pk) } ?: break
            members.remove(orphan)
            changed = true
        }

        for ((pk, hello) in hellos.toList()) {
            if (members.any { it.pk == pk }) continue
            val proposal = proposals[pk] ?: continue
            if (pk !in issued || !proposalStands(pk)) continue
            val role = if (proposal.p.role == Role.CHILD) MemberRole.CHILD else MemberRole.ADULT
            val entry = StateMember(pk, role, proposal.p.name ?: hello.second, proposal.by, now,
                book.halvesFor(roomId, pk).map { it.event }, if (role == MemberRole.CHILD) family.statementsFor(pk) else emptyList())
            val verdict = checkNewcomer(s.copy(members = members), entry, now)
            if (verdict !is Verdict.Ok) continue
            members += entry.copy(approvals = verdict.pairs.flatMap { listOf(it.child.event, it.guardian.event) })
            if (role == MemberRole.CHILD) for (g in proposal.p.guardians) {
                if (members.none { it.pk == g }) members += StateMember(g, MemberRole.GUARDIAN, null, proposal.by, now)
            }
            changed = true
        }

        for ((invitee, prop) in proposals.toList()) {
            if (invitee in issued || members.any { it.pk == invitee } || !proposalStands(invitee)) continue
            if (s.members.any { it.pk == invitee }) continue
            if (members.none { it.pk == prop.by && it.role != MemberRole.GUARDIAN }) continue
            if (prop.p.expires <= now) continue
            val needed = requiredChildren(s.copy(members = members), invitee, prop.p.role).filter { it != invitee }
            val complete = needed.all { c ->
                val pair = book.pair(roomId, invitee, c, now)
                pair != null && pair.approval.role == prop.p.role && pair.approval.guardians.sorted() == prop.p.guardians.sorted()
            }
            if (complete) invite(invitee, prop.p, prop.by)
        }

        val draft = s.copy(members = members)
        val readers = computeReaders(draft, tierOf)
        val readersChanged = readers != s.readers
        val emergency = rekeyAfterEmergency.isNotEmpty()
        rekeyAfterEmergency.clear()
        if (!changed && !readersChanged && !emergency) return

        val dropped = s.readers.filter { it !in readers }
        val removed = (s.removed.filter { it !in readers } + dropped).distinct()
            .filter { r -> members.none { it.pk == r && it.role != MemberRole.GUARDIAN } }
        val nextEpoch = s.epoch + 1
        val nextSecret = Entropy.bytes(32)
        val recipients = readers.flatMap { devices[it]?.keys ?: emptySet() }
        val currentSecret = epochs[s.epoch] ?: return
        opts.transport.publish(encodeRekeyEvent(roomId, sk, deriveEpoch(RoomEpoch(s.epoch, currentSecret)), RoomEpoch(nextEpoch, nextSecret),
            recipients, dropped, now, commit = true, members = readers, scheduled = dropped.isEmpty() && emergency && !changed))
        adoptEpoch(nextEpoch, nextSecret)
        publishState(s.copy(seq = s.seq + 1, prev = head.event.id, epoch = nextEpoch, members = members, readers = readers, removed = removed))
    }

    private fun departedAt(pk: String): Long? {
        var inRoom = false
        var left: Long? = null
        for (a in accepted) {
            val present = a.state.members.any { it.pk == pk }
            if (inRoom && !present) left = a.event.createdAt
            inRoom = present
        }
        return if (inRoom) null else left
    }

    private fun proposalStands(pk: String): Boolean {
        val p = proposals[pk] ?: return false
        val left = departedAt(pk)
        return left == null || p.at > left
    }

    private suspend fun invite(invitee: String, p: Payload.Propose, by: String) {
        val bearer = Entropy.bytes(32)
        val invitation = RoomInvitation(bearer, authority, true)
        opts.transport.publish(encodePersistentInvitation(RoomInvitationHost(invitation, authoritySk!!), secret, now()))
        issued[invitee] = bearer.toHex()
        val s = head()!!.state
        val envelope = InvitationEnvelope(roomId, s.name, bearer.toHex(), authority, invitee, p.role, p.guardians, s.member(by)?.name)
        for (r in if (p.role == Role.CHILD) p.guardians else listOf(invitee)) {
            opts.transport.publish(wrapInvitation(opts.identity, r, envelope, now()))
        }
    }

    private suspend fun publishState(state: RoomState) {
        val event = signState(requireNotNull(authoritySk) { "only the authority publishes state" }, state, now())
        post(Payload.State(event), lobby = true, "The room changed.")
    }

    // ------------------------------------------------------------ acting

    private suspend fun post(payload: Payload?, lobby: Boolean, text: String, epoch: Int = current, retracts: String? = null): String {
        val cred = credential ?: error("the device has no credential yet")
        val stream = if (lobby) this.lobby else {
            val s = epochs[epoch]
            check(s != null && epoch != 0) { "this device cannot write to the room yet" }
            deriveEpoch(RoomEpoch(epoch, s)).let { Stream(it.id, it.key) }
        }
        val now = now()
        val msg = RoomMessage(newMessageId(), me, device, cred, text, now, opts.name, now * 1000 + System.currentTimeMillis() % 1000, retracts, payload)
        val event = encodeMessage(msg, stream, deviceSk)
        opts.transport.publish(event)
        onEvent(event)
        return msg.id
    }

    suspend fun send(text: String): String = withContext(engine) { post(null, lobby = false, text) }

    suspend fun retract(id: String) = withContext(engine) {
        val m = messages[id] ?: return@withContext
        if (m.msg.participant != me || m.epoch !in epochs) return@withContext
        post(null, lobby = false, "Message withdrawn", m.epoch, retracts = id)
    }

    suspend fun postStatements(events: List<NostrEvent>, text: String = "Updated family details.") = withContext(engine) {
        if (events.isEmpty()) return@withContext
        family.addAll(events); tiers.addAll(events); book.addAll(events)
        val approvals = events.any { readApproval(it) != null }
        val canWrite = current > 0 && current in epochs && head()?.state?.readers?.contains(me) == true
        post(Payload.Statements(events), lobby = !(approvals && canWrite), text)
    }

    suspend fun propose(invitee: String, role: Role, name: String? = null, guardians: List<String> = emptyList()) = withContext(engine) {
        val expires = now() + opts.approvalSeconds
        post(Payload.Propose(invitee, role, guardians.sorted(), name, expires), lobby = false, "I'd like to invite ${name ?: "someone"}. Everyone's approval is needed.")
        approve(invitee)
    }

    fun approvalsToSign(invitee: String): List<String> {
        val s = head()?.state ?: return emptyList()
        val prop = proposals[invitee] ?: return emptyList()
        return requiredChildren(s, invitee, prop.p.role).filter { it != invitee }.filter { c ->
            (c == me || family.isGuardian(me, c)) &&
                book.halvesFor(roomId, invitee).none { it.signer == me && it.approval.child == c && it.approval.expires == prop.p.expires }
        }
    }

    suspend fun approve(invitee: String): Int = withContext(engine) {
        val prop = proposals[invitee] ?: error("no such proposal")
        val events = approvalsToSign(invitee).map { child ->
            opts.identity.sign(approvalUnsigned(Approval(roomId, invitee, child, prop.p.role, prop.p.guardians, prop.p.expires), now(), prop.p.name))
        }
        if (events.isNotEmpty()) postStatements(events, "I approve ${prop.p.name ?: "the invitation"}.")
        events.size
    }

    suspend fun remove(target: String) = withContext(engine) {
        post(Payload.Remove(target), lobby = false, if (target == me) "I am leaving this room." else "I asked for someone to be removed.")
    }

    // ------------------------------------------------------------ guardians

    private fun gatedFor(s: RoomState?): List<String> {
        if (s?.member(me)?.role != MemberRole.GUARDIAN) return emptyList()
        return s.children.filter { family.isGuardian(me, it.pk) && (tierOf.tier(it.pk)?.n ?: 1) > 1 }.map { it.pk }
    }

    private fun belongs(epoch: Int, participant: String, sentAt: Long): Boolean {
        var listed = false
        var leftAt: Long? = null
        for (a in accepted) {
            if (a.state.epoch == epoch && participant in a.state.readers) listed = true
            if (a.state.epoch > epoch && leftAt == null) leftAt = a.event.createdAt
        }
        return listed && (leftAt == null || sentAt <= leftAt + MAX_CLOCK_SKEW_SECONDS)
    }

    private fun ordered(): List<Held> = messages.values.filter { belongs(it.epoch, it.msg.participant, it.msg.sentAt) }
        .sortedWith(compareBy<Held> { it.msg.sentAtMs ?: (it.msg.sentAt * 1000) }.thenBy { it.msg.id })

    /** A guardian past the first tier: publish the receipt, then read. */
    suspend fun reveal() = withContext(engine) {
        val gated = gatedFor(head()?.state)
        val ids = ordered().filter { !it.viaEmergency }.map { it.msg.id }
        if (gated.isEmpty() || ids.isEmpty()) { revealed += ids; return@withContext }
        if (ids.all { it in revealed }) return@withContext
        for (child in gated) post(Payload.Read(child, ids.last(), now()), lobby = false, "${opts.name ?: "A guardian"} read this conversation.")
        revealed += ids
    }

    /** A guardian of a tier-3 child: announce, then open. */
    suspend fun openInEmergency(child: String, reason: String? = null): Int = withContext(engine) {
        val s = head()?.state ?: error("the room is not loaded yet")
        check(family.isGuardian(me, child)) { "only a guardian of this child can open their messages" }
        if (!opts.identity.canSeal) throw SealRefused()
        val escrow = escrows["$child:${s.epoch}"]
        val copy = escrow?.copies?.firstOrNull { it.first == me }
        check(escrow != null && copy != null) { "there is nothing sealed for you for this epoch" }
        val notice = opts.identity.sign(emergencyUnsigned(EmergencyNotice(roomId, child, s.epoch, reason), now()))
        post(Payload.Emergency(notice), lobby = true, "${opts.name ?: "A guardian"} opened messages in an emergency.")
        val opened = opts.identity.nip44Decrypt(escrow.device, copy.second).hexToBytes()
        check(opened.size == 32) { "escrowed key is malformed" }
        this@KincludeRoom.opened[s.epoch] = opened
        watchEpoch(s.epoch)
        s.epoch
    }

    // ------------------------------------------------------------ calls

    fun callContext(): CallContext {
        val cred = credential ?: error("the device has no credential yet")
        val s = head()?.state
        val epochSecret = s?.let { epochs[it.epoch] }
        val reading = s != null && epochSecret != null && current >= s.epoch && me in s.readers
        return CallContext(roomId, me, device, deviceSk, cred, opts.transport, opts.now,
            if (reading) s!!.epoch to deriveEpoch(RoomEpoch(s.epoch, epochSecret!!)).key else null, s?.readers ?: emptyList())
    }

    suspend fun postCall(call: String, join: Boolean, video: Boolean) = withContext(engine) {
        val who = opts.name ?: "Someone"
        post(Payload.Call(call, join, video), lobby = false, if (join) "$who joined the ${if (video) "video" else "voice"} call." else "$who left the call.")
    }

    // ------------------------------------------------------------ views

    suspend fun view(): RoomView = withContext(engine) { snapshot() }

    private fun snapshot(): RoomView {
        val s = head()?.state
        val tierMap = s?.children?.associate { it.pk to tierOf.tier(it.pk) } ?: emptyMap()
        val gated = gatedFor(s)
        val all = ordered()
        val visible = if (gated.isNotEmpty()) all.filter { it.msg.id in revealed || it.viaEmergency } else all
        val props = if (s == null) emptyList() else proposals.mapNotNull { (invitee, prop) ->
            val m = s.member(invitee)
            if ((m != null && m.role != MemberRole.GUARDIAN) || prop.p.expires <= now()) return@mapNotNull null
            val halves = book.halvesFor(roomId, invitee).filter { it.approval.expires == prop.p.expires }
            Proposal(invitee, prop.p.name, prop.p.role, prop.p.guardians, prop.by, prop.at, prop.p.expires,
                requiredChildren(s, invitee, prop.p.role).filter { it != invitee }.map { c ->
                    ProposalApproval(c, halves.any { it.signer == c && it.approval.child == c },
                        halves.firstOrNull { it.approval.child == c && it.signer != c && family.isGuardian(it.signer, c) }?.signer)
                }, invitee in issued)
        }
        val openable = s?.children?.filter { c -> family.isGuardian(me, c.pk) && escrows["${c.pk}:${s.epoch}"]?.copies?.any { it.first == me } == true }?.map { it.pk } ?: emptyList()
        val log = callRecords.filter { belongs(it.epoch, it.r.participant, it.r.at) }.map { it.r }.sortedBy { it.at }
        val latest = LinkedHashMap<String, CallRecord>().also { m -> log.forEach { m[it.participant] = it } }
        val joined = latest.values.filter { it.join && it.at >= now() - CALL_RECORD_HOURS * 3600 }.sortedByDescending { it.at }
        val call = joined.firstOrNull()?.let { newest -> CallNow(newest.call, joined.filter { it.call == newest.call }) }
        return RoomView(
            roomId, s?.name ?: "Loading…", s, stateProblem, creator, me, s?.member(me)?.role, current,
            s != null && current >= s.epoch && me in s.readers,
            removedMe || (s != null && me in s.removed),
            if (s != null) banners(s, tierOf) else emptyList(), tierMap,
            visible.map { ShownMessage(it.msg.id, it.msg.participant, it.msg.name, it.msg.text, it.msg.sentAt, it.epoch, it.viaEmergency) },
            all.size - visible.size, gated, props,
            receipts.filter { belongs(it.epoch, it.participant, it.sentAt) }.map { it.r },
            emergencies.toList(), openable, call, log,
        )
    }
}
