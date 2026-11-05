package app.kinclude.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinclude.AppGraph
import app.kinclude.core.Household
import app.kinclude.core.KincludeRoom
import app.kinclude.core.MemberRole
import app.kinclude.core.Role
import app.kinclude.core.RoomView
import app.kinclude.core.Tier
import app.kinclude.core.mayRemove
import kotlinx.coroutines.launch

private sealed interface Line {
    val at: Long
    data class Message(val id: String, val from: String, val name: String?, val text: String, override val at: Long, val emergency: Boolean) : Line
    data class Note(val text: String, override val at: Long, val alert: Boolean = false) : Line
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(graph: AppGraph, roomId: String, back: () -> Unit, call: () -> Unit) {
    val household by graph.household.collectAsStateWithLifecycle()
    val h = household ?: return
    val room = h.rooms[roomId] ?: run { back(); return }
    val data = fromGraph(graph, roomId) { room.view() to h.views().values.toList() } ?: return
    val (v, all) = data
    val names = { pk: String -> if (pk == h.me) "You" else nameOf(h, all, pk) }
    val scope = rememberCoroutineScope()
    var people by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }

    // A guardian who chose to read keeps reading while the room is open:
    // each new message is receipted before it is shown, never after.
    LaunchedEffect(reading, v.unrevealed) { if (reading && v.unrevealed > 0) runCatching { room.reveal() } }
    LaunchedEffect(v.messages.lastOrNull()?.id) { v.messages.lastOrNull()?.let { graph.markRead(roomId, it.sentAt) } }
    // No notifications for the room on screen.
    androidx.compose.runtime.DisposableEffect(roomId) {
        graph.notifier.visibleRoom = roomId
        onDispose { if (graph.notifier.visibleRoom == roomId) graph.notifier.visibleRoom = null }
    }

    val startCall = rememberCall(graph, room, onStarted = call)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Row(Modifier.clickable { people = true }, verticalAlignment = Alignment.CenterVertically) {
                        Avatar(v.name, 36.dp)
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(v.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(list(v.state?.members?.map { names(it.pk) } ?: emptyList()), style = MaterialTheme.typography.labelSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    if (v.reading) {
                        IconButton({ startCall(true) }) { Icon(Icons.Filled.Videocam, "Video call") }
                        IconButton({ startCall(false) }) { Icon(Icons.Filled.Call, "Voice call") }
                    }
                },
            )
        },
        bottomBar = {
            if (v.reading) Composer(text, { text = it }) {
                val t = text.trim()
                if (t.isNotEmpty()) scope.launch { runCatching { room.send(t) }; text = ""; graph.changed() }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OversightLines(h, v, names) { people = true }
            v.stateProblem?.let { Notice("This room changed in a way this phone could not check: $it. It is showing the last state it could check.") }
            if (v.gatedFor.isNotEmpty() && v.unrevealed > 0 && !reading) {
                val child = v.gatedFor.first()
                ActionCard(
                    "${names(child)} is at ${v.tierOf[child]?.title ?: Tier.GROWING_UP.title}. If you read this conversation, ${names(child)} will see that you did. ${v.unrevealed} message${if (v.unrevealed == 1) "" else "s"} not yet read.",
                    "Read, and show that I did",
                ) { reading = true }
            }
            if (v.openable.isNotEmpty()) EmergencyCard(v.openable.first(), names) { reason ->
                scope.launch { runCatching { room.openInEmergency(v.openable.first(), reason) }; graph.changed() }
            }
            if (!v.reading && !v.removed && v.openable.isEmpty()) Text(
                if (v.role == MemberRole.GUARDIAN) "You are listed here as a guardian, and cannot read this room at the moment."
                else "Waiting to be let in. Everyone the room needed has to agree first.",
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (v.removed && v.role != MemberRole.GUARDIAN) Notice("You are no longer in this room.")
            for (p in v.proposals) if (room.approvalsToSign(p.invitee).isNotEmpty()) ActionCard(
                "${names(p.by)} would like to invite ${p.name ?: names(p.invitee)}.", "Approve",
            ) { scope.launch { room.approve(p.invitee); graph.changed() } }
            Timeline(timeline(v, h, names), h.me, names, Modifier.weight(1f))
        }
    }
    if (people) PeopleSheet(graph, h, room, v, names) { people = false }
}

private fun timeline(v: RoomView, h: Household, names: (String) -> String): List<Line> {
    val out = ArrayList<Line>()
    v.messages.forEach { out += Line.Message(it.id, it.from, it.name, it.text, it.sentAt, it.viaEmergency) }
    // The latest receipt from each guardian, as WhatsApp shows "seen".
    v.receipts.groupBy { it.guardian to it.child }.values.map { r -> r.maxBy { it.at } }
        .forEach { out += Line.Note("${names(it.guardian)} read this conversation · ${clockOf(it.at)}", it.at) }
    // Calls are history too; a guardian who has not read in the open sees them with the rest.
    if (v.unrevealed == 0) v.callLog.forEach {
        out += Line.Note("${names(it.participant)} ${if (it.join) "joined the ${if (it.video) "video" else "voice"} call" else "left the call"} · ${clockOf(it.at)}", it.at)
    }
    v.emergencies.forEach {
        out += Line.Note("${names(it.signer)} opened ${if (it.notice.child == h.me) "your" else names(it.notice.child) + "'s"} messages in an emergency · ${clockOf(it.at)}" +
            (it.notice.reason?.let { r -> "\n\"$r\"" } ?: ""), it.at, alert = true)
    }
    return out.sortedBy { it.at }
}

@Composable
private fun Timeline(lines: List<Line>, me: String, names: (String) -> String, modifier: Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1) }
    LazyColumn(modifier.fillMaxWidth(), state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(lines, key = { l -> when (l) { is Line.Message -> l.id; is Line.Note -> l.text + l.at } }) { l ->
            when (l) {
                is Line.Message -> Bubble(l, mine = l.from == me, name = l.name ?: names(l.from))
                is Line.Note -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(8.dp),
                        color = if (l.alert) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Text(l.text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            color = if (l.alert) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Line.Message, mine: Boolean, name: String) {
    val dark = MaterialTheme.colorScheme.background == Brand.Room
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp),
            color = if (mine) (if (dark) Brand.MineDark else Brand.Mine) else MaterialTheme.colorScheme.surfaceVariant,
            border = if (m.emergency) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (!mine) Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                Text(clockOf(m.at), Modifier.align(Alignment.End), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, send: () -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            text, onText, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
        )
        IconButton(send, Modifier.padding(start = 6.dp).size(48.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp))) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/** The line under the top bar that says who can read this room, for each child in it. */
@Composable
private fun OversightLines(h: Household, v: RoomView, names: (String) -> String, onTap: () -> Unit) {
    for (b in v.banners) {
        val tier = b.tier ?: Tier.FIRST_MESSAGES
        val child = if (b.child == h.me) "your" else names(b.child) + "'s"
        val who = { pks: List<String> -> list(pks.map(names)) }
        val (text, colour) = when {
            b.catchingUp -> "This room hasn't caught up with $child new oversight yet. Until it does, the people shown can still see it." to MaterialTheme.colorScheme.errorContainer
            b.reading.isNotEmpty() && tier == Tier.GROWING_UP -> "${who(b.reading)} can read $child messages here, and everyone sees when they have. · ${tier.title}" to Brand.LeafSoft
            b.reading.isNotEmpty() -> "${who(b.reading)} can see this chat. · ${tier.title}" to Brand.LeafSoft
            b.notReading.isNotEmpty() -> "${who(b.notReading)} can't see this chat. · ${tier.title}" to MaterialTheme.colorScheme.surfaceVariant
            else -> continue
        }
        Surface(Modifier.fillMaxWidth().clickable(onClick = onTap), color = colour) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge,
                color = if (colour == Brand.LeafSoft) Brand.Leaf else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun ActionCard(text: String, action: String, onAction: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text)
            Button(onAction) { Text(action) }
        }
    }
}

@Composable
private fun EmergencyCard(child: String, names: (String) -> String, open: (String?) -> Unit) {
    var reason by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${names(child)}'s messages are theirs. In an emergency you can open them, and ${names(child)} is told straight away. Everyone in the room sees that you did.")
            OutlinedTextField(reason, { reason = it.take(280) }, Modifier.fillMaxWidth(), label = { Text("What should ${names(child)} be told?") })
            OutlinedButton({ confirm = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Open in an emergency") }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Open ${names(child)}'s messages?") },
        text = { Text("${names(child)} will be told straight away, and so will everyone in this room.") },
        confirmButton = { TextButton({ confirm = false; open(reason.trim().ifEmpty { null }) }) { Text("Open them") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

/** Who is in the room, adding people, leaving. */
@Composable
private fun PeopleSheet(graph: AppGraph, h: Household, room: KincludeRoom, v: RoomView, names: (String) -> String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val s = v.state
    val inRoom = s?.members?.map { it.pk }?.toSet() ?: emptySet()
    AlertDialog(
        onDismissRequest = close,
        title = { Text(v.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s?.members?.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(names(m.pk), 32.dp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(names(m.pk), fontWeight = FontWeight.SemiBold)
                            Text(when (m.role) {
                                MemberRole.GUARDIAN -> if (m.pk in s.readers) "guardian, can read" else "guardian, cannot read"
                                MemberRole.CHILD -> "child · ${v.tierOf[m.pk]?.title ?: ""}"
                                MemberRole.ADULT -> ""
                            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val creator = v.creator
                        if (m.pk != h.me && creator != null && mayRemove(s, h.me, m.pk, creator)) TextButton({ scope.launch { room.remove(m.pk); close() } }) { Text("Remove") }
                    }
                }
                if (v.role == MemberRole.ADULT) {
                    HorizontalDivider()
                    Text("Invite", style = MaterialTheme.typography.titleSmall)
                    Text("Everyone already here has to agree, and a child's guardian with them.", style = MaterialTheme.typography.bodySmall)
                    h.data.children.filter { it.pk !in inRoom }.forEach { c ->
                        OutlinedButton({ scope.launch { runCatching { h.addChild(room, c.pk) }; graph.changed(); close() } }, Modifier.fillMaxWidth()) { Text("Add ${c.name ?: names(c.pk)}") }
                    }
                    h.data.contacts.filter { it.pk !in inRoom }.forEach { c ->
                        OutlinedButton({ scope.launch { room.propose(c.pk, Role.ADULT, c.name); graph.changed(); close() } }, Modifier.fillMaxWidth()) { Text("Invite ${c.name ?: names(c.pk)}") }
                    }
                    if (h.data.children.isEmpty() && h.data.contacts.isEmpty()) Text("Pair with a child, or add people you know, in the Family tab first.", style = MaterialTheme.typography.bodySmall)
                }
                if (v.creator != h.me && v.role != null && v.role != MemberRole.GUARDIAN) {
                    Spacer(Modifier.size(4.dp))
                    TextButton({ scope.launch { room.remove(h.me); close() } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Leave this room") }
                }
            }
        },
        confirmButton = { TextButton(close) { Text("Done") } },
    )
}

/** Ask for the microphone (and camera), join the room's call, then go to the call screen. */
@Composable
fun rememberCall(graph: AppGraph, room: KincludeRoom, onStarted: () -> Unit): (Boolean) -> Unit {
    val scope = rememberCoroutineScope()
    var wantVideo by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) scope.launch {
            val call = graph.callFor(room)
            runCatching { call.join(wantVideo && granted[Manifest.permission.CAMERA] == true) }
            onStarted()
        }
    }
    return { video ->
        wantVideo = video
        launcher.launch(if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA) else arrayOf(Manifest.permission.RECORD_AUDIO))
    }
}
