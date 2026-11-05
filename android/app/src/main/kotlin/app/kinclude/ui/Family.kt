package app.kinclude.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kinclude.AppGraph
import app.kinclude.core.Contact
import app.kinclude.core.Household
import app.kinclude.core.Npub
import app.kinclude.core.PairingOffer
import app.kinclude.core.Tier
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.launch

@Composable
fun FamilyTab(graph: AppGraph, h: Household) {
    val views = fromGraph(graph) { h.views() } ?: emptyMap()
    val names = { pk: String -> nameOf(h, views.values, pk) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (h.data.child) ChildFamily(graph, h, names) else GuardianFamily(graph, h, names)
        if (!h.data.child) People(graph, h)
        ThisPhone(graph, h)
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun GuardianFamily(graph: AppGraph, h: Household, names: (String) -> String) {
    var pairing by remember { mutableStateOf(false) }
    Section("Children")
    if (h.data.children.isEmpty()) Text("Pair with your child's phone, together, in person. Nobody can reach a child's phone any other way.",
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    h.data.children.forEach { c ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.name ?: names(c.pk), 40.dp)
                Text(c.name ?: names(c.pk), Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.SemiBold)
                h.tierOf(c.pk)?.let { AssistChip({}, { Text(it.title) }) }
            }
            h.tierOf(c.pk)?.let { TierMove(graph, h, c.pk, it, forChild = false) }
        }
    }
    Button({ pairing = true }, Modifier.fillMaxWidth()) { Text("Pair with a child's phone") }
    if (h.data.children.isNotEmpty()) Text("Already paired with another parent? Pair again; the child keeps the oversight you agreed.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (pairing) PairDialog(graph, h) { pairing = false }
}

@Composable
private fun ChildFamily(graph: AppGraph, h: Household, names: (String) -> String) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Section("Family")
    if (h.data.guardians.isEmpty()) Text("Not paired with anyone yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    h.data.guardians.forEach { g ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(g.name ?: names(g.pk), 40.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(g.name ?: names(g.pk), fontWeight = FontWeight.SemiBold)
                Text("guardian", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    h.tierOf(h.me)?.let { tier ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your oversight", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                AssistChip({}, { Text(tier.title) })
            }
            Text(tier.helpForChild, style = MaterialTheme.typography.bodySmall)
            TierMove(graph, h, h.me, tier, forChild = true)
        }
    }
    Panel {
        Text("Pair with a parent or carer", fontWeight = FontWeight.SemiBold)
        Text("On their phone, they choose Pair with a child's phone. Paste the code it shows.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), label = { Text("kinclude-pair:…") }, minLines = 2)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            busy = true; error = null
            scope.launch {
                runCatching { h.acceptPairing(code) }.onSuccess { code = "" }.onFailure { error = it.message ?: "That did not work." }
                busy = false; graph.changed()
            }
        }, enabled = code.isNotBlank() && !busy) { Text(if (busy) "Pairing…" else "Pair") }
    }
}

@Composable
private fun TierMove(graph: AppGraph, h: Household, child: String, current: Tier, forChild: Boolean) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    val waiting = h.tiers.pending(child).any { it.signer == h.me }
    if (waiting) { Text("Waiting for the other side to agree.", style = MaterialTheme.typography.bodySmall); return }
    Column {
        OutlinedButton({ open = true }) { Text("Ask to move") }
        DropdownMenu(open, { open = false }) {
            Tier.entries.filter { it != current }.forEach { t ->
                DropdownMenuItem(
                    text = { Column { Text(t.title, fontWeight = FontWeight.SemiBold); Text(if (forChild) t.helpForChild else t.help, style = MaterialTheme.typography.bodySmall) } },
                    onClick = { open = false; scope.launch { h.proposeTier(child, t); graph.changed() } },
                )
            }
        }
    }
}

@Composable
private fun PairDialog(graph: AppGraph, h: Household, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf(Tier.FIRST_MESSAGES) }
    var offer by remember { mutableStateOf<PairingOffer?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    DisposableEffect(offer) { onDispose { offer?.cancel?.invoke() } }
    LaunchedEffect(offer) { offer?.paired?.await()?.let { graph.changed(); close() } }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (offer == null) "Pair with a child's phone" else "On the child's phone") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val o = offer
                if (o == null) {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Child's name") }, singleLine = true)
                    Text("Where you start together", style = MaterialTheme.typography.labelLarge)
                    Tier.entries.forEach { t ->
                        Row(Modifier.fillMaxWidth().selectable(tier == t, onClick = { tier = t }, role = Role.RadioButton), verticalAlignment = Alignment.Top) {
                            RadioButton(tier == t, null)
                            Column(Modifier.padding(start = 8.dp)) { Text(t.title, fontWeight = FontWeight.SemiBold); Text(t.help, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else {
                    Text("Choose Family, then Pair, and paste this code. Do it together, in person.")
                    val bitmap = remember(o.code) { qr(o.code) }
                    Box(Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(12.dp), contentAlignment = Alignment.Center) {
                        Image(bitmap.asImageBitmap(), "Pairing code", Modifier.size(220.dp))
                    }
                    Text(o.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ clipboard.setText(AnnotatedString(o.code)) }) { Text("Copy") }
                        OutlinedButton({
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, o.code), "Share pairing code"))
                        }) { Text("Share") }
                    }
                    Text("Waiting for the child's phone…", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (offer == null) TextButton({ offer = h.offerPairing(tier, name.trim().ifEmpty { null }) }) { Text("Show code") }
        },
        dismissButton = { TextButton(close) { Text("Cancel") } },
    )
}

private fun qr(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.RGB_565)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) bmp.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    return bmp
}

@Composable
private fun People(graph: AppGraph, h: Household) {
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var kin by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    Section("People you know")
    Text("Only people here can invite you, and only they can be invited by you. With My Signet this list will be the kin and kith you have met.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    h.data.contacts.forEach { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(c.name ?: c.pk.take(2), 36.dp)
            Text(c.name ?: Npub.encode(c.pk).take(16) + "…", Modifier.weight(1f).padding(start = 12.dp))
            AssistChip({}, { Text(if (c.kin) "Kin" else "Kith") })
            TextButton({ h.setContacts(h.data.contacts.filter { it.pk != c.pk }); graph.changed() }) { Text("Remove") }
        }
    }
    Panel {
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Their key (npub1…)") }, singleLine = true)
        OutlinedTextField(name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kin, { kin = true }, { Text("Kin") })
            FilterChip(!kin, { kin = false }, { Text("Kith") })
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button({
            val pk = Npub.decode(key)
            if (pk == null) error = "That is not a person's key." else {
                h.setContacts(h.data.contacts.filter { it.pk != pk } + Contact(pk, name.trim().ifEmpty { null }, kin))
                key = ""; name = ""; error = null; graph.changed()
            }
        }, enabled = key.isNotBlank()) { Text("Add") }
    }
}

@Composable
private fun ThisPhone(graph: AppGraph, h: Household) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var relays by remember { mutableStateOf(graph.relays.joinToString("\n")) }
    var ice by remember { mutableStateOf(graph.iceServers.joinToString("\n")) }
    var forget by remember { mutableStateOf(false) }
    val npub = Npub.encode(h.me)
    Section("This phone")
    Panel {
        Text("${h.data.name}${if (h.data.child) " · child's phone" else ""}", fontWeight = FontWeight.SemiBold)
        Text(npub, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ clipboard.setText(AnnotatedString(npub)) }) { Text("Copy my key") }
            OutlinedButton({
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, npub), "Share my key"))
            }) { Text("Share") }
        }
    }
    Panel {
        Text("Relays", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(relays, { relays = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
        Text("Call servers", fontWeight = FontWeight.SemiBold)
        Text("A STUN server lets two phones on different networks find each other; it sees each phone's address, not the call. A TURN server carries a call when nothing else gets through: add a username and password after its address.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(ice, { ice = it }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
        OutlinedButton({
            graph.relays = relays.split(Regex("\\s+")).filter { it.startsWith("ws") }
            graph.iceServers = ice.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        }) { Text("Save (applies next time Kinclude starts)") }
    }
    OutlinedButton({ forget = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Forget this phone") }
    if (forget) AlertDialog(
        onDismissRequest = { forget = false },
        title = { Text("Forget this phone?") },
        text = { Text("Your rooms and key kept on this phone are deleted. Nobody can bring them back.") },
        confirmButton = { TextButton({ forget = false; graph.forget() }) { Text("Forget") } },
        dismissButton = { TextButton({ forget = false }) { Text("Cancel") } },
    )
}
