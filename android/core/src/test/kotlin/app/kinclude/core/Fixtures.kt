package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

/** Shared test set-up: people, a relay, a clock, and the household the
 *  TypeScript scenario tests use (Dad runs the room, Sam is in it, Mum is
 *  listed as Sam's guardian). */
object Clock { @Volatile var t = 1_800_000_000L }
val now: () -> Long = { Clock.t }

class Person(val name: String, child: Boolean = false) {
    val sk: ByteArray = Entropy.bytes(32)
    val signer = LocalSigner(sk, dependant = child)
    val pk: String get() = signer.pubkey
}

fun adult(name: String) = Person(name)
fun kid(name: String) = Person(name, child = true)

suspend fun linkEvents(guardian: Person, child: Person, at: Long = now()): List<NostrEvent> = listOf(
    guardian.signer.sign(guardianOfUnsigned(child.pk, at)),
    child.signer.sign(dependantOfUnsigned(guardian.pk, at)),
)

suspend fun tierEvents(child: Person, guardian: Person, tier: Tier, seq: Int = 0, prev: String = TIER_GENESIS, at: Long = now()): Pair<String, List<NostrEvent>> {
    val s = TierStatement(child.pk, tier, seq, prev)
    return tierDigest(s) to listOf(child.signer.sign(tierUnsigned(s, at, child.name)), guardian.signer.sign(tierUnsigned(s, at, child.name)))
}

fun newRelay() = MemoryRelay(CoroutineScope(SupervisorJob() + Dispatchers.Default))

/** Wait until the relay has been quiet for a while and every room has run
 *  what it holds. */
suspend fun quiet(relay: MemoryRelay, rooms: List<KincludeRoom>) {
    var last = -1
    var still = 0
    var i = 0
    while (i++ < 600 && still < 5) {
        delay(5)
        rooms.forEach { it.idle() }
        if (relay.log.size == last) still++ else { still = 0; last = relay.log.size }
    }
}

suspend fun inbox(relay: MemoryRelay, who: Person): List<InvitationEnvelope> =
    relay.stored.filter { e -> e.kind == GIFT_WRAP_KIND && e.tags.any { it.size >= 2 && it[0] == "p" && it[1] == who.pk } }
        .mapNotNull { unwrapInvitation(it, who.signer)?.first }

suspend fun join(relay: MemoryRelay, who: Person, env: InvitationEnvelope, family: List<NostrEvent> = emptyList()): KincludeRoom {
    val secret = requestPersistentAdmission(relay.connect(), RoomInvitation(env.bearer.hexToBytes(), env.authority, true))
    val room = KincludeRoom(RoomOptions(relay.connect(), who.signer, who.name, RoomSecrets(secret.toHex(), env.authority),
        Entropy.bytes(32), family, now, requestEveryMs = 0))
    check(room.roomId == env.room) { "the invitation opened a different room" }
    room.start()
    return room
}

suspend fun ownPair(env: InvitationEnvelope, child: Person, guardian: Person): List<NostrEvent> {
    val a = Approval(env.room, child.pk, child.pk, Role.CHILD, env.guardians, now() + APPROVAL_TTL_SECONDS)
    return listOf(child.signer.sign(approvalUnsigned(a, now())), guardian.signer.sign(approvalUnsigned(a, now())))
}

suspend fun familyOf(child: Person, guardians: List<Person>, tier: Tier): Pair<String, List<NostrEvent>> {
    val events = ArrayList<NostrEvent>()
    for (g in guardians) events += linkEvents(g, child)
    val (digest, t) = tierEvents(child, guardians.first(), tier)
    return digest to events + t
}

class TestHousehold(
    val relay: MemoryRelay, val dad: Person, val mum: Person, val sam: Person, val genesis: String,
    val dadRoom: KincludeRoom, val samRoom: KincludeRoom, val mumRoom: KincludeRoom,
) {
    val rooms = listOf(dadRoom, samRoom, mumRoom)
    fun close() = rooms.forEach { it.close() }
}

suspend fun household(tier: Tier): TestHousehold {
    val relay = newRelay()
    val dad = adult("Dad"); val mum = adult("Mum"); val sam = kid("Sam")
    val (digest, fam) = familyOf(sam, listOf(dad, mum), tier)
    val dadRoom = KincludeRoom.create(RoomOptions(relay.connect(), dad.signer, "Dad", RoomSecrets("", ""), Entropy.bytes(32), fam, now, requestEveryMs = 0), "Home")
    dadRoom.propose(sam.pk, Role.CHILD, "Sam", listOf(dad.pk, mum.pk))
    quiet(relay, listOf(dadRoom))
    val invite = inbox(relay, mum).single()
    val samRoom = join(relay, sam, invite, fam + ownPair(invite, sam, mum))
    quiet(relay, listOf(dadRoom, samRoom))
    val mumRoom = join(relay, mum, invite, fam)
    quiet(relay, listOf(dadRoom, samRoom, mumRoom))
    return TestHousehold(relay, dad, mum, sam, digest, dadRoom, samRoom, mumRoom)
}

suspend fun moveTier(h: TestHousehold, tier: Tier, seq: Int, prev: String): String {
    val (digest, events) = tierEvents(h.sam, h.mum, tier, seq, prev)
    h.samRoom.postStatements(listOf(events[0]))
    h.mumRoom.postStatements(listOf(events[1]))
    quiet(h.relay, h.rooms)
    return digest
}
