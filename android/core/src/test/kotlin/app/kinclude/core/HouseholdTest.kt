package app.kinclude.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `test/household.test.ts`, in Kotlin. */
class HouseholdTest {
    private suspend fun open(relay: MemoryRelay, p: Person, child: Boolean) =
        Household.open(relay.connect(), p.signer, MemoryStore(), p.name, child, now).also { it.start() }

    private suspend fun settle(relay: MemoryRelay, hs: List<Household>) {
        repeat(3) { quiet(relay, hs.flatMap { it.rooms.values }); delay(20) }
    }

    @Test fun pairsBringsTheChildInAndAgreesATier() = runBlocking {
        val relay = newRelay()
        val mum = open(relay, adult("Mum"), false)
        val dad = open(relay, adult("Dad"), false)
        val sam = open(relay, kid("Sam"), true)
        val nan = open(relay, adult("Nan"), false)
        val all = listOf(mum, dad, sam, nan)

        val offer = mum.offerPairing(Tier.GROWING_UP, "Sam")
        sam.acceptPairing(offer.code)
        assertEquals(sam.me, offer.paired.await().pk)
        settle(relay, all)
        assertEquals(Tier.GROWING_UP, mum.tierOf(sam.me))
        assertEquals(Tier.GROWING_UP, sam.tierOf(sam.me))
        val offer2 = dad.offerPairing(Tier.FIRST_MESSAGES)
        sam.acceptPairing(offer2.code)
        offer2.paired.await()
        settle(relay, all)
        assertEquals(Tier.GROWING_UP, dad.tierOf(sam.me))
        assertEquals(listOf(dad.me, mum.me).sorted(), sam.family.guardiansOf(sam.me))
        assertEquals("Sam's phone told Mum about Dad", listOf(dad.me, mum.me).sorted(), mum.family.guardiansOf(sam.me))

        mum.setContacts(listOf(Contact(dad.me, "Dad", true), Contact(nan.me, "Nan", true)))
        dad.setContacts(listOf(Contact(mum.me, "Mum", true)))
        nan.setContacts(listOf(Contact(mum.me, "Mum", true)))

        val room = mum.createRoom("Home")
        mum.addChild(room, sam.me)
        settle(relay, all)
        mum.acceptInvite(mum.data.invites.single { it.forChild == sam.me }.id)
        dad.acceptInvite(dad.data.invites.single { it.forChild == sam.me }.id)
        settle(relay, all)
        val samInvite = sam.data.invites.single()
        assertEquals("Home", samInvite.envelope.name)
        sam.acceptInvite(samInvite.id)
        settle(relay, all)

        val samRoom = sam.rooms.getValue(room.roomId)
        val dadRoom = dad.rooms.getValue(room.roomId)
        samRoom.view().let { assertEquals(MemberRole.CHILD, it.role); assertTrue(it.reading) }
        dadRoom.view().let { assertEquals(MemberRole.GUARDIAN, it.role); assertTrue(it.reading); assertEquals(listOf(sam.me), it.gatedFor) }

        room.propose(nan.me, Role.ADULT, "Nan")
        settle(relay, all)
        assertTrue(nan.data.invites.isEmpty())
        samRoom.approve(nan.me)
        settle(relay, all)
        nan.acceptInvite(nan.data.invites.single().id)
        settle(relay, all)
        assertTrue(nan.rooms.getValue(room.roomId).view().reading)

        sam.proposeTier(sam.me, Tier.NEARLY_GROWN)
        settle(relay, all)
        val waiting = dad.pendingTiers().single()
        assertEquals(Tier.NEARLY_GROWN, waiting.tier)
        assertEquals(sam.me, waiting.signedBy)
        dad.agreeTier(waiting)
        settle(relay, all)
        for (h in all) assertEquals(Tier.NEARLY_GROWN, h.rooms.getValue(room.roomId).view().tierOf[sam.me])
        assertTrue(!dadRoom.view().reading)
        all.forEach { it.close() }
    }

    @Test fun aStrangersInvitationIsDropped() = runBlocking {
        val relay = newRelay()
        val stranger = adult("Stranger")
        val nan = open(relay, adult("Nan"), false)
        relay.connect().publish(wrapInvitation(stranger.signer, nan.me,
            InvitationEnvelope("a".repeat(64), "Free stuff", "b".repeat(64), stranger.pk, nan.me, Role.ADULT, emptyList()), now()))
        delay(200)
        assertTrue(nan.data.invites.isEmpty())
        nan.close()
    }

    @Test fun readsTheTypeScriptPairingCode() {
        val c = decodePairingCode(Vectors.str("derived", "pairingCode"))!!
        assertEquals(Vectors.str("pubkeys", "guardian"), c.guardian)
        assertEquals(Tier.GROWING_UP, c.tier)
        assertEquals("Sam", c.childName)
        assertEquals("Mum", c.guardianName)
    }
}
