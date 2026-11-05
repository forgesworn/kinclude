package app.kinclude.core

import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/** A relay subscription filter: the parts of NIP-01 Kinclude uses. */
data class Filter(
    val kinds: List<Int>,
    /** Tag filters, by tag letter: `mapOf("d" to listOf(roomId))` is `#d`. */
    val tags: Map<String, List<String>> = emptyMap(),
) {
    fun matches(e: NostrEvent): Boolean =
        e.kind in kinds && tags.all { (name, values) -> e.tags.any { it.size >= 2 && it[0] == name && it[1] in values } }

    companion object {
        fun of(vararg kinds: Int, d: String? = null, p: String? = null) = Filter(
            kinds.toList(),
            buildMap { d?.let { put("d", listOf(it)) }; p?.let { put("p", listOf(it)) } },
        )
    }
}

/**
 * The relay seam: fold-kit's `RelayTransport` in Kotlin. The app's
 * implementation talks to real relays over OkHttp; [MemoryRelay] is the
 * in-process one the tests run on. [publish] returns once a relay has
 * accepted the event: that acknowledgement is what a receipt and an
 * emergency notice wait for.
 */
interface RelayTransport {
    suspend fun publish(event: NostrEvent)
    fun subscribe(filters: List<Filter>, onEvent: (NostrEvent) -> Unit, onEose: (() -> Unit)? = null): () -> Unit
}

/**
 * A relay in this process. Regular events are stored and replayed to each
 * new subscription before its end-of-stored-events; ephemeral ones
 * (20000-29999) go to whoever is listening. Delivery is launched on
 * [scope], never inline, so tests cannot lean on an order a network would
 * not give them.
 */
class MemoryRelay(private val scope: CoroutineScope) {
    val stored = CopyOnWriteArrayList<NostrEvent>()
    val log = CopyOnWriteArrayList<NostrEvent>()
    private class Sub(val filters: List<Filter>, val onEvent: (NostrEvent) -> Unit) { @Volatile var open = true }
    private val subs = CopyOnWriteArrayList<Sub>()

    fun accept(e: NostrEvent) {
        synchronized(this) {
            if (log.any { it.id == e.id }) return
            log += e
            if (e.kind !in 20000..29999) stored += e
        }
        for (s in subs) if (s.open && s.filters.any { it.matches(e) }) scope.launch { if (s.open) s.onEvent(e) }
    }

    fun connect(): RelayTransport = object : RelayTransport {
        override suspend fun publish(event: NostrEvent) { accept(event) }
        override fun subscribe(filters: List<Filter>, onEvent: (NostrEvent) -> Unit, onEose: (() -> Unit)?): () -> Unit {
            val sub = Sub(filters, onEvent)
            scope.launch {
                for (e in stored.toList()) if (sub.open && filters.any { it.matches(e) }) sub.onEvent(e)
                if (sub.open) { subs += sub; onEose?.invoke() }
            }
            return { sub.open = false; subs.remove(sub) }
        }
    }
}
