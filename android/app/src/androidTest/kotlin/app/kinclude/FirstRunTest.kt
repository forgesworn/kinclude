package app.kinclude

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app on a real (emulated) phone, against the offline relay CI runs on
 * the host (`test/ws-relay.mjs`, reached from the emulator at 10.0.2.2):
 * set up, make a room, send a message, see it in the chat and in the list.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunTest {
    init {
        // Before the activity starts: this phone uses the offline relay.
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("kinclude", Context.MODE_PRIVATE).edit()
            .putString("relays", "ws://10.0.2.2:7788").putString("ice", "stun:127.0.0.1:9").commit()
    }

    @get:Rule(order = 0) val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *(if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyArray()))

    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, timeoutMs: Long = 20_000) =
        compose.waitUntil(timeoutMs) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    @Test fun setUpMakeARoomAndSayHello() {
        waitFor("Whose phone is this?")
        compose.onNode(hasSetTextAction() and hasText("Name shown to your family")).performTextInput("Mum")
        compose.onNodeWithText("Keep a key on this phone", substring = true).let {
            runCatching { it.performClick() }.onFailure { compose.onNodeWithText("Start").performClick() }
        }
        waitFor("No chats yet")
        compose.onNodeWithContentDescription("New room").performClick()
        compose.onNode(hasSetTextAction() and hasText("Name, e.g. Home")).performTextInput("Home")
        compose.onNodeWithText("Make room").performClick()
        // In the new room, once this phone can write to it, the message box appears.
        compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction() and hasText("Message")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Message")).performTextInput("Hello from the emulator")
        compose.onNodeWithContentDescription("Send").performClick()
        waitFor("Hello from the emulator")
        compose.onNodeWithText("Hello from the emulator").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        waitFor("You: Hello from the emulator")
    }
}
