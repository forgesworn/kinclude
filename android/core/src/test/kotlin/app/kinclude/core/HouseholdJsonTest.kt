package app.kinclude.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseholdJsonTest {
    @Test fun aHouseholdReadsBackAsItWasWritten() = runBlocking {
        val mum = adult("Mum"); val sam = kid("Sam")
        val links = linkEvents(mum, sam)
        val env = InvitationEnvelope("a".repeat(64), "Home", "b".repeat(64), "c".repeat(64), sam.pk, Role.CHILD, listOf(mum.pk), "Mum")
        val d = HouseholdData(
            name = "Mum", child = false, phoneSecret = "d".repeat(64), family = links,
            children = listOf(KnownChild(sam.pk, "Sam", "e".repeat(64))),
            guardians = emptyList(), contacts = listOf(Contact("f".repeat(64), "Nan", kin = true), Contact("1".repeat(64), null, kin = false)),
            rooms = listOf(RoomSecrets("2".repeat(64), "3".repeat(64), "4".repeat(64), mapOf(1 to "5".repeat(64), 2 to "6".repeat(64)), mapOf(sam.pk to "7".repeat(64)))),
            handled = listOf("x", "y"),
            invites = listOf(PendingInvite("w", env, mum.pk, links, forChild = sam.pk)),
        )
        assertEquals(d, HouseholdJson.read(HouseholdJson.write(d)))
    }

    @Test fun nonsenseReadsAsNothing() {
        assertNull(HouseholdJson.read("{"))
        assertNull(HouseholdJson.read("{\"name\":\"x\"}"))
    }
}
