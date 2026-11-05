package app.kinclude.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Statements signed by the TypeScript library, read by the Kotlin one. */
class StatementsVectorsTest {
    private val guardian = Vectors.str("pubkeys", "guardian")
    private val child = Vectors.str("pubkeys", "child")
    private val t = 1_800_000_000L

    @Test fun linksMakeAGuardianAndAnUnlinkEndsIt() {
        assertEquals(LinkType.GUARDIAN_OF, readLinkStatement(Vectors.event("links", "guardianOf"))?.type)
        assertEquals(LinkType.DEPENDANT_OF, readLinkStatement(Vectors.event("links", "dependantOf"))?.type)
        val g = FamilyGraph()
        g.addAll(listOf(Vectors.event("links", "guardianOf"), Vectors.event("links", "dependantOf")))
        assertTrue(g.isGuardian(guardian, child))
        g.add(Vectors.event("links", "unlink"))
        assertFalse(g.isGuardian(guardian, child))
    }

    @Test fun approvalPairIsComplete() {
        val g = FamilyGraph()
        g.addAll(listOf(Vectors.event("links", "guardianOf"), Vectors.event("links", "dependantOf")))
        val book = ApprovalBook(g)
        book.addAll(listOf(Vectors.event("approval", "byChild"), Vectors.event("approval", "byGuardian")))
        val room = Vectors.str("room", "id")
        val pair = book.pair(room, Vectors.str("pubkeys", "nan"), child, t)
        assertNotNull(pair)
        assertEquals(guardian, pair!!.guardian.signer)
        assertEquals(Role.ADULT, pair.approval.role)
    }

    @Test fun tierChainAndDigestsAgree() {
        val genesis = TierStatement(child, Tier.GROWING_UP, 0, TIER_GENESIS)
        assertEquals(Vectors.str("tier", "genesis", "digest"), tierDigest(genesis))
        val up = TierStatement(child, Tier.NEARLY_GROWN, 1, tierDigest(genesis))
        assertEquals(Vectors.str("tier", "up", "digest"), tierDigest(up))
        val g = FamilyGraph()
        g.addAll(listOf(Vectors.event("links", "guardianOf"), Vectors.event("links", "dependantOf")))
        val ledger = TierLedger(g)
        ledger.addAll(listOf("genesisByChild", "genesisByGuardian", "upByChild", "upByGuardian").map { Vectors.event("tier", it) })
        val pos = ledger.current(child)!!
        assertEquals(Tier.NEARLY_GROWN, pos.tier)
        assertEquals(1, pos.seq)
        assertEquals(4, ledger.chainEvents(child).size)
    }

    @Test fun emergencyNoticeReads() {
        val e = readEmergency(Vectors.event("emergency", "event"))!!
        assertEquals(guardian, e.signer)
        assertEquals(1, e.notice.epoch)
        assertEquals("Your phone has been off since 9", e.notice.reason)
    }
}
