package app.kinclude.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinclude.AppGraph

/** Where the app is. A phone app with a handful of screens needs no more. */
sealed interface Screen {
    data class Home(val tab: Tab = Tab.CHATS) : Screen
    data class Chat(val roomId: String) : Screen
    data class Call(val roomId: String) : Screen
}

enum class Tab { CHATS, CALLS, FAMILY }

@Composable
fun KincludeApp(graph: AppGraph) {
    val household by graph.household.collectAsStateWithLifecycle()
    val starting by graph.starting.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            household == null -> Setup(graph)
            else -> Navigator(graph)
        }
    }
}

@Composable
private fun Navigator(graph: AppGraph) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Home())) }
    val go: (Screen) -> Unit = { stack = stack + it }
    val back: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
    val home: (Tab) -> Unit = { tab -> stack = listOf(Screen.Home(tab)) }
    BackHandler(enabled = stack.size > 1) { back() }
    when (val s = stack.last()) {
        is Screen.Home -> HomeScreen(graph, s.tab, onTab = home, open = go)
        is Screen.Chat -> ChatScreen(graph, s.roomId, back = back, call = { go(Screen.Call(s.roomId)) })
        is Screen.Call -> CallScreen(graph, s.roomId, back = back)
    }
}

/** First run: whose phone this is, and a name. */
@Composable
private fun Setup(graph: AppGraph) {
    var child by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Kinclude", fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp)
        Text("Family messaging with no way in from outside. Everyone in it is someone you both chose.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text("Whose phone is this?", style = MaterialTheme.typography.titleMedium)
        Choice(!child, "Mine: I'm a parent, carer or relative", "You can invite people you know and pair with a child's phone.") { child = false }
        Choice(child, "A child's phone", "Pairs with a parent or carer, in person. Nobody can find it from outside.") { child = true }
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Name shown to your family") }, singleLine = true)
        Text(
            "Your key is kept on this phone, sealed by Android's keystore. Signing in with My Signet, so the key is never on the phone at all, comes next.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button({ graph.setUp(child, name) }, Modifier.fillMaxWidth().height(52.dp), enabled = name.isNotBlank()) { Text("Start") }
    }
}

@Composable
private fun Choice(selected: Boolean, title: String, detail: String, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            RadioButton(selected, onClick = null)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
