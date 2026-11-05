package app.kinclude.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinclude.AppGraph
import app.kinclude.core.Household
import app.kinclude.core.RoomView
import app.kinclude.core.Tier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Load something from the household, again whenever it changes. */
@Composable
fun <T> fromGraph(graph: AppGraph, vararg keys: Any?, load: suspend () -> T): T? {
    val tick by graph.tick.collectAsStateWithLifecycle()
    val value by produceState<T?>(null, tick, *keys) { value = runCatching { load() }.getOrNull() }
    return value
}

/** Every room's view, by room id. */
suspend fun Household.views(): Map<String, RoomView> = rooms.mapValues { it.value.view() }

/** What to call somebody: what they are called in this household, or in a room, or a short key. */
fun nameOf(household: Household, views: Collection<RoomView>, pk: String): String {
    if (pk == household.me) return household.data.name
    household.data.children.firstOrNull { it.pk == pk }?.name?.let { return it }
    household.data.guardians.firstOrNull { it.pk == pk }?.name?.let { return it }
    household.data.contacts.firstOrNull { it.pk == pk }?.name?.let { return it }
    for (v in views) v.state?.member(pk)?.name?.let { return it }
    return "${pk.take(6)}…${pk.takeLast(4)}"
}

fun list(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> names[0]
    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
}

private val clock = SimpleDateFormat("HH:mm", Locale.UK)
private val day = SimpleDateFormat("d MMM", Locale.UK)

fun timeOf(seconds: Long): String {
    val d = Date(seconds * 1000)
    val today = day.format(Date()) == day.format(d)
    return if (today) clock.format(d) else day.format(d)
}

fun clockOf(seconds: Long): String = clock.format(Date(seconds * 1000))

val Tier.help: String get() = when (this) {
    Tier.FIRST_MESSAGES -> "You see every message. They know you do."
    Tier.GROWING_UP -> "You can read their messages, and they see exactly what you have read."
    Tier.NEARLY_GROWN -> "Their messages are theirs. In an emergency you can open them, and they are told straight away."
}

val Tier.helpForChild: String get() = when (this) {
    Tier.FIRST_MESSAGES -> "Your guardians see every message, and you know they do."
    Tier.GROWING_UP -> "Your guardians can read your messages, and you see exactly what they have read."
    Tier.NEARLY_GROWN -> "Your messages are yours. In an emergency a guardian can open them, and you are told straight away."
}

/** A round initial, as chat apps show a person or a room without a picture. */
@Composable
fun Avatar(name: String, size: Dp = 48.dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifEmpty { "?" }, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.42f).sp, color = MaterialTheme.colorScheme.primary)
    }
}
