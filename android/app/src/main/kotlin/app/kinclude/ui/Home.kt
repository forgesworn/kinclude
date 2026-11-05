package app.kinclude.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinclude.AppGraph
import app.kinclude.core.Household
import app.kinclude.core.PendingTier
import app.kinclude.core.Proposal
import app.kinclude.core.Role
import app.kinclude.core.RoomView
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(graph: AppGraph, tab: Tab, onTab: (Tab) -> Unit, open: (Screen) -> Unit) {
    val household by graph.household.collectAsStateWithLifecycle()
    val h = household ?: return
    var newRoom by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(when (tab) { Tab.CHATS -> "Kinclude"; Tab.CALLS -> "Calls"; Tab.FAMILY -> "Family" },
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold)
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.CHATS, { onTab(Tab.CHATS) }, { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("Chats") })
                NavigationBarItem(tab == Tab.CALLS, { onTab(Tab.CALLS) }, { Icon(Icons.Filled.Call, null) }, label = { Text("Calls") })
                NavigationBarItem(tab == Tab.FAMILY, { onTab(Tab.FAMILY) }, { Icon(Icons.Filled.FamilyRestroom, null) }, label = { Text("Family") })
            }
        },
        floatingActionButton = {
            if (tab == Tab.CHATS && !h.data.child) FloatingActionButton({ newRoom = true }) { Icon(Icons.Filled.Edit, "New room") }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.CHATS -> ChatsTab(graph, h, open)
                Tab.CALLS -> CallsTab(graph, h, open)
                Tab.FAMILY -> FamilyTab(graph, h)
            }
        }
    }
    if (newRoom) NewRoomDialog(graph, h, onDone = { id -> newRoom = false; id?.let { open(Screen.Chat(it)) } })
}

@Composable
private fun NewRoomDialog(graph: AppGraph, h: Household, onDone: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text("New room") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You can add your children and the people you know once it is made. Everyone already in it has to agree to each newcomer.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name, e.g. Home") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton({ scope.launch { val r = h.createRoom(name.trim().ifEmpty { "Family" }); graph.changed(); onDone(r.roomId) } }) { Text("Make room") }
        },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } },
    )
}

private data class ChatsData(val views: Map<String, RoomView>, val approvals: List<Pair<RoomView, Proposal>>, val tiers: List<PendingTier>)

@Composable
private fun ChatsTab(graph: AppGraph, h: Household, open: (Screen) -> Unit) {
    val data = fromGraph(graph) {
        val views = h.views()
        val approvals = h.rooms.values.flatMap { r ->
            val v = views.getValue(r.roomId)
            v.proposals.filter { r.approvalsToSign(it.invitee).isNotEmpty() }.map { v to it }
        }
        ChatsData(views, approvals, h.pendingTiers())
    } ?: return
    val scope = rememberCoroutineScope()
    val names = { pk: String -> nameOf(h, data.views.values, pk) }
    val rows = data.views.values.sortedWith(compareByDescending<RoomView> { v -> v.messages.lastOrNull()?.sentAt ?: 0 })
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        // Like Signal's message requests: what is waiting on you comes first.
        items(h.data.invites, key = { "i" + it.id }) { invite ->
            Waiting(
                when {
                    invite.forChild != null -> "${names(invite.sender)} invites ${names(invite.forChild!!)} to ${invite.envelope.name}. Saying yes passes it to ${names(invite.forChild!!)}'s phone, and lists you as their guardian there."
                    h.data.child -> "${names(invite.sender)} passed on an invitation to ${invite.envelope.name}."
                    else -> "${names(invite.sender)} invites you to ${invite.envelope.name}."
                },
                yes = "Accept" to { scope.launch { runCatching { h.acceptInvite(invite.id) }; graph.changed() } },
                no = "No thanks" to { h.declineInvite(invite.id) },
            )
        }
        items(data.approvals, key = { "a" + it.first.roomId + it.second.invitee }) { (v, p) ->
            val who = p.name ?: names(p.invitee)
            Waiting(
                "${names(p.by)} would like to invite $who to ${v.name}" + if (p.role == Role.CHILD) ", with ${list(p.guardians.map(names))} as their guardian${if (p.guardians.size > 1) "s" else ""}." else ".",
                yes = "Approve" to { scope.launch { h.rooms[v.roomId]?.approve(p.invitee); graph.changed() } },
                no = "Open room" to { open(Screen.Chat(v.roomId)) },
            )
        }
        items(data.tiers, key = { "t" + it.child + it.seq }) { t ->
            Waiting(
                "${names(t.signedBy)} would like ${if (t.child == h.me) "you" else names(t.child)} to move to ${t.tier.title}. " +
                    if (t.child == h.me) t.tier.helpForChild else t.tier.help,
                yes = "Agree" to { scope.launch { h.agreeTier(t); graph.changed() } },
                no = null,
            )
        }
        if (rows.isEmpty()) item {
            Text(
                if (h.data.child) "No chats yet. When a parent or carer adds you to a room, it shows here." else "No chats yet. Make a room with the pencil button, then add your family.",
                Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rows, key = { it.roomId }) { v ->
            val last = v.messages.lastOrNull()
            val unread = v.messages.count { it.sentAt > graph.lastRead(v.roomId) && it.from != h.me }
            ListItem(
                modifier = Modifier.clickable { open(Screen.Chat(v.roomId)) },
                leadingContent = { Avatar(v.name) },
                headlineContent = { Text(v.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(
                        when {
                            v.call != null -> "Call in progress"
                            last != null -> (if (last.from == h.me) "You: " else "${last.name ?: names(last.from)}: ") + last.text
                            v.reading -> "No messages yet"
                            v.removed -> "You cannot read this room now"
                            else -> "Waiting to be let in…"
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        last?.let { Text(timeOf(it.sentAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (v.call != null) Icon(Icons.Filled.Videocam, "Call in progress", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                        else if (unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("$unread") }
                    }
                },
            )
            HorizontalDivider(Modifier.padding(start = 80.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun Waiting(text: String, yes: Pair<String, () -> Unit>, no: Pair<String, () -> Unit>?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(yes.second) { Text(yes.first) }
                no?.let { OutlinedButton(it.second) { Text(it.first) } }
            }
        }
    }
}

@Composable
private fun CallsTab(graph: AppGraph, h: Household, open: (Screen) -> Unit) {
    val views = fromGraph(graph) { h.views() } ?: return
    val entries = views.values.flatMap { v -> v.callLog.filter { it.join }.map { v to it } }.sortedByDescending { it.second.at }
    if (entries.isEmpty()) {
        Text("No calls yet. Start one from a chat with the phone or camera button.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(entries, key = { it.first.roomId + it.second.participant + it.second.at }) { (v, c) ->
            ListItem(
                modifier = Modifier.clickable { open(Screen.Chat(v.roomId)) },
                leadingContent = { Avatar(v.name) },
                headlineContent = { Text(v.name, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    Text("${if (c.participant == h.me) "You" else nameOf(h, views.values, c.participant)} joined a ${if (c.video) "video" else "voice"} call · ${timeOf(c.at)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    IconButton({ open(Screen.Call(v.roomId)) }) {
                        Icon(if (c.video) Icons.Filled.Videocam else Icons.Filled.Call, "Call ${v.name}", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        }
    }
}

@Composable
fun Dot(modifier: Modifier = Modifier) {
    Surface(modifier.size(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary) {}
}
