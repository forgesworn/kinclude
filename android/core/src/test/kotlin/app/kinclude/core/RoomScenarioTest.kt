package app.kinclude.core

import dev.forgesworn.kithmoot.crypto.Entropy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The TypeScript scenario tests (`test/scenario.test.ts`), in Kotlin. */
class RoomScenarioTest {
    private fun texts(v: RoomView) = v.messages.map { it.text }

    @Test fun familyRoomWithDualApproval() = runBlocking {
        val relay = newRelay()
        val mum = adult("Mum"); val sam = kid("Sam"); val nan = adult("Nan")
        val (_, fam) = familyOf(sam, listOf(mum), Tier.FIRST_MESSAGES)
        val mumRoom = KincludeRoom.create(RoomOptions(relay.connect(), mum.signer, "Mum", RoomSecrets("", ""), Entropy.bytes(32), fam, now, requestEveryMs = 0), "Home")
        quiet(relay, listOf(mumRoom))
        mumRoom.view().let { assertEquals("Home", it.name); assertTrue(it.reading); assertEquals(MemberRole.ADULT, it.role) }

        mumRoom.propose(sam.pk, Role.CHILD, "Sam", listOf(mum.pk))
        quiet(relay, listOf(mumRoom))
        val samInvite = inbox(relay, mum).single()
        assertEquals(sam.pk, samInvite.invitee)
        val samRoom = join(relay, sam, samInvite, fam + ownPair(samInvite, sam, mum))
        quiet(relay, listOf(mumRoom, samRoom))
        samRoom.view().let { assertEquals(MemberRole.CHILD, it.role); assertTrue(it.reading); assertEquals(Tier.FIRST_MESSAGES, it.tierOf[sam.pk]) }

        samRoom.send("hello mum")
        quiet(relay, listOf(mumRoom, samRoom))
        assertEquals(listOf("hello mum"), texts(mumRoom.view()))

        mumRoom.propose(nan.pk, Role.ADULT, "Nan")
        quiet(relay, listOf(mumRoom, samRoom))
        assertTrue("no invitation before Sam agrees", inbox(relay, nan).isEmpty())
        assertEquals(listOf(sam.pk), samRoom.approvalsToSign(nan.pk))
        samRoom.approve(nan.pk)
        quiet(relay, listOf(mumRoom, samRoom))
        val nanRoom = join(relay, nan, inbox(relay, nan).single())
        quiet(relay, listOf(mumRoom, samRoom, nanRoom))
        assertTrue(nanRoom.view().reading)
        nanRoom.send("Did you see the heron this morning?")
        quiet(relay, listOf(mumRoom, samRoom, nanRoom))
        assertTrue("Did you see the heron this morning?" in texts(samRoom.view()))
        assertFalse("hello mum" in texts(nanRoom.view()))
        for (r in listOf(mumRoom, samRoom, nanRoom)) assertNull(r.view().stateProblem)
        listOf(mumRoom, samRoom, nanRoom).forEach { it.close() }
    }

    @Test fun aLeakedRoomSecretNeverReads() = runBlocking {
        val relay = newRelay()
        val mum = adult("Mum")
        val mumRoom = KincludeRoom.create(RoomOptions(relay.connect(), mum.signer, "Mum", RoomSecrets("", ""), Entropy.bytes(32), now = now, requestEveryMs = 0), "Home")
        quiet(relay, listOf(mumRoom))
        val stranger = adult("Stranger")
        val leaked = KincludeRoom(RoomOptions(relay.connect(), stranger.signer, "Stranger", mumRoom.secrets().copy(authoritySk = null, epochs = emptyMap()), Entropy.bytes(32), now = now, requestEveryMs = 0))
        leaked.start()
        mumRoom.send("private")
        quiet(relay, listOf(mumRoom, leaked))
        leaked.view().let { assertFalse(it.reading); assertNull(it.role); assertTrue(it.messages.isEmpty()) }
        assertEquals(listOf(mum.pk), mumRoom.view().state!!.members.map { it.pk })
        mumRoom.close(); leaked.close()
    }

    @Test fun growingUpReceiptBeforeReading() = runBlocking {
        val h = household(Tier.GROWING_UP)
        h.samRoom.send("Are you coming on Saturday?")
        quiet(h.relay, h.rooms)
        h.mumRoom.view().let { assertTrue(it.reading); assertEquals(listOf(h.sam.pk), it.gatedFor); assertEquals(1, it.unrevealed); assertTrue(it.messages.isEmpty()) }
        val before = h.relay.log.size
        h.mumRoom.reveal()
        assertTrue("the receipt went out first", h.relay.log.size > before)
        assertEquals(listOf("Are you coming on Saturday?"), texts(h.mumRoom.view()))
        quiet(h.relay, h.rooms)
        assertEquals(h.mum.pk, h.samRoom.view().receipts.single().guardian)
        h.close()
    }

    @Test fun nearlyGrownRekeysOutAndOpeningIsAnnouncedFirst() = runBlocking {
        val h = household(Tier.GROWING_UP)
        h.samRoom.send("before")
        quiet(h.relay, h.rooms)
        val epochBefore = h.dadRoom.view().epoch
        moveTier(h, Tier.NEARLY_GROWN, 1, h.genesis)
        for (r in h.rooms) assertEquals(Tier.NEARLY_GROWN, r.view().tierOf[h.sam.pk])
        assertEquals(epochBefore + 1, h.dadRoom.view().epoch)
        assertFalse(h.mumRoom.view().reading)
        h.samRoom.send("Text me when you're home.")
        quiet(h.relay, h.rooms)
        assertFalse("Text me when you're home." in texts(h.mumRoom.view()))
        assertEquals(listOf(h.sam.pk), h.mumRoom.view().openable)

        val epoch = h.mumRoom.openInEmergency(h.sam.pk, "Your phone has been off since 9")
        quiet(h.relay, h.rooms)
        assertEquals(epochBefore + 1, epoch)
        assertEquals("Your phone has been off since 9", h.samRoom.view().emergencies.single().notice.reason)
        val opened = h.mumRoom.view().messages
        assertTrue("Text me when you're home." in opened.map { it.text })
        assertTrue(opened.all { it.viaEmergency })
        assertEquals(epoch + 1, h.dadRoom.view().epoch)
        h.samRoom.send("after the opening")
        quiet(h.relay, h.rooms)
        assertFalse("after the opening" in texts(h.mumRoom.view()))
        h.close()
    }

    @Test fun noOpeningWithoutAnEscrow() = runBlocking {
        val h = household(Tier.FIRST_MESSAGES)
        try { h.mumRoom.openInEmergency(h.sam.pk); fail("opened without an escrow") } catch (_: IllegalStateException) {}
        assertTrue(h.samRoom.view().emergencies.isEmpty())
        h.close()
    }

    @Test fun movingDownNeedsBothAndDoesNotHandOverHistory() = runBlocking {
        val h = household(Tier.NEARLY_GROWN)
        assertFalse(h.mumRoom.view().reading)
        h.samRoom.send("mine alone")
        quiet(h.relay, h.rooms)
        val (_, half) = tierEvents(h.sam, h.mum, Tier.FIRST_MESSAGES, 1, h.genesis)
        h.mumRoom.postStatements(listOf(half[1]))
        quiet(h.relay, h.rooms)
        assertEquals(Tier.NEARLY_GROWN, h.dadRoom.view().tierOf[h.sam.pk])
        h.samRoom.postStatements(listOf(half[0]))
        quiet(h.relay, h.rooms)
        assertEquals(Tier.FIRST_MESSAGES, h.dadRoom.view().tierOf[h.sam.pk])
        assertTrue(h.mumRoom.view().reading)
        assertFalse("mine alone" in texts(h.mumRoom.view()))
        h.samRoom.send("Mum can see this")
        quiet(h.relay, h.rooms)
        assertEquals(listOf("Mum can see this"), texts(h.mumRoom.view()))
        h.close()
    }

    @Test fun removalTakesTheListedGuardianAndHistoryStaysShut() = runBlocking {
        val h = household(Tier.FIRST_MESSAGES)
        h.samRoom.remove(h.sam.pk)
        quiet(h.relay, h.rooms)
        assertEquals(listOf(h.dad.pk), h.dadRoom.view().state!!.members.map { it.pk })
        assertFalse(h.samRoom.view().reading)
        assertFalse(h.mumRoom.view().reading)
        Clock.t += 3600
        h.samRoom.send("written after I left")
        quiet(h.relay, h.rooms)
        Clock.t -= 3600
        assertFalse("written after I left" in texts(h.dadRoom.view()))
        h.close()
    }
}
