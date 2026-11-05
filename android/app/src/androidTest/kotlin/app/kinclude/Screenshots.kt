package app.kinclude

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.test.services.storage.TestStorage

/** Save what the phone shows, for the CI artifact (`additional_test_output`). */
fun ComposeTestRule.screenshot(name: String) {
    runCatching {
        waitForIdle()
        val bitmap = onRoot().captureToImage().asAndroidBitmap()
        TestStorage().openOutputFile("$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Everything written on screen, for a failure message. */
fun ComposeTestRule.onScreen(): String = runCatching {
    onAllNodes(isRoot()).fetchSemanticsNodes().flatMap { root ->
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            (n.config.getOrNull(SemanticsProperties.Text)?.map { it.text } ?: emptyList()) +
                (n.config.getOrNull(SemanticsProperties.EditableText)?.let { listOf("[" + it.text + "]") } ?: emptyList()) +
                (n.config.getOrNull(SemanticsProperties.ContentDescription) ?: emptyList()).map { "<$it>" } +
                n.children.flatMap(::walk)
        walk(root)
    }.joinToString(" | ").take(1500)
}.getOrElse { "(could not read the screen: $it)" }

/** Wait for text to appear; on timeout, say what was on screen instead. */
fun ComposeTestRule.waitForText(text: String, timeoutMs: Long = 30_000) {
    try {
        waitUntil(timeoutMs) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        screenshot("failed-waiting-for-" + text.take(20).replace(Regex("[^A-Za-z0-9]"), "_"))
        throw AssertionError("Never saw \"$text\". On screen: ${onScreen()}", e)
    }
}
