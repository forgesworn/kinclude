package app.kinclude

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.kinclude.core.Contact
import app.kinclude.core.Household
import app.kinclude.core.LocalSigner
import app.kinclude.core.MemoryStore
import app.kinclude.core.Npub
import app.kinclude.core.Role
import app.kinclude.net.RelayPool
import dev.forgesworn.kithmoot.crypto.Entropy
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two people: Nan is this app, driven through its screens; Mum is a
 * Kinclude household running inside the test on the same relay. Mum makes
 * a room and invites Nan; Nan, who knows Mum, accepts; they talk.
 */
@RunWith(AndroidJUnit4::class)
class TwoPeopleTest {
    init {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("kinclude", Context.MODE_PRIVATE).edit()
            .putString("relays", RELAY).putString("ice", "stun:127.0.0.1:9").commit()
    }

    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, timeoutMs: Long = 30_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun mumInvitesNanAndTheyTalk() = runBlocking {
        val graph = (ApplicationProvider.getApplicationContext<Context>() as KincludeApplication).graph
        // Nan sets this phone up.
        waitFor("Whose phone is this?")
        compose.onNode(hasSetTextAction() and hasText("Name shown to your family")).performTextInput("Nan")
        compose.onNodeWithText("Start").performClick()
        waitFor("No chats yet")
        val nan = graph.household.value!!.me

        // Mum, on her own phone (here, in the test).
        val mum = Household.open(RelayPool(listOf(RELAY)), LocalSigner(Entropy.bytes(32)), MemoryStore(), "Mum")
        mum.start()
        mum.setContacts(listOf(Contact(nan, "Nan", kin = true)))

        // Nan adds Mum as somebody she knows, in the Family tab.
        compose.onNodeWithText("Family").performClick()
        compose.onNode(hasSetTextAction() and hasText("Their key (npub1…)")).performScrollTo().performTextInput(Npub.encode(mum.me))
        compose.onNode(hasSetTextAction() and hasText("Name")).performTextInput("Mum")
        compose.onNodeWithText("Add").performScrollTo().performClick()
        compose.waitUntil(10_000) { graph.household.value!!.data.contacts.any { it.pk == mum.me } }
        compose.onNodeWithText("Chats").performClick()

        // Mum makes a room and invites Nan; with no children in it, her word is enough.
        val room = mum.createRoom("Home")
        room.propose(nan, Role.ADULT, "Nan")
        waitFor("Mum invites you to Home")
        compose.onNodeWithText("Accept").performClick()
        waitFor("Home")

        // Once Nan is let in, Mum says hello and Nan sees it in the list and the chat.
        repeat(60) { if (room.view().state?.member(nan) != null) return@repeat; delay(500) }
        room.send("Did you see the heron this morning?")
        waitFor("Mum: Did you see the heron this morning?")
        compose.onNodeWithText("Home").performClick()
        waitFor("Did you see the heron this morning?")
        compose.waitUntil(30_000) { compose.onAllNodes(hasSetTextAction() and hasText("Message")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Message")).performTextInput("Yes! It was huge.")
        compose.onNodeWithContentDescription("Send").performClick()

        // And Mum's phone gets Nan's reply.
        var heard = false
        repeat(60) { if (room.view().messages.any { it.text == "Yes! It was huge." }) { heard = true; return@repeat }; delay(500) }
        check(heard) { "Mum never got Nan's reply" }
        mum.close()
    }

    companion object { const val RELAY = "ws://10.0.2.2:7788" }
}
