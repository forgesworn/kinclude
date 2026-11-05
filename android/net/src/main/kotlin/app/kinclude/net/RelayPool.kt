package app.kinclude.net

import app.kinclude.core.Filter
import app.kinclude.core.RelayTransport
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Kinclude's relays, over OkHttp WebSockets: the Kotlin counterpart of the
 * web client's `poolTransport`.
 *
 * - **Publish** resolves when any relay says OK, and throws when none does
 *   in time. That acknowledgement is what a receipt and an emergency notice
 *   wait for before anything is shown or opened.
 * - **Subscribe** asks every relay, delivers each event once, and reports
 *   end-of-stored-events when every relay that is up has sent it, or after
 *   [eoseTimeoutMs].
 * - **Reconnection** is automatic, with back-off; subscriptions are asked
 *   again on reconnect.
 */
class RelayPool(
    private val urls: List<String>,
    private val client: OkHttpClient = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).build(),
    private val publishTimeoutMs: Long = 10_000,
    private val eoseTimeoutMs: Long = 6_000,
) : RelayTransport {
    init { require(urls.isNotEmpty()) { "at least one relay" } }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextSub = AtomicInteger()

    private inner class Sub(val id: String, val filters: List<Filter>, val onEvent: (NostrEvent) -> Unit, val onEose: (() -> Unit)?) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val eosed = ConcurrentHashMap.newKeySet<String>()
        @Volatile var eoseReported = false
        @Volatile var open = true

        fun reportEose() {
            if (eoseReported) return
            eoseReported = true
            onEose?.invoke()
        }

        fun req(): String = JsonArray(listOf(JsonPrimitive("REQ"), JsonPrimitive(id)) + filters.map { f ->
            buildJsonObject {
                put("kinds", JsonArray(f.kinds.map { JsonPrimitive(it) }))
                f.tags.forEach { (k, v) -> put("#$k", JsonArray(v.map { JsonPrimitive(it) })) }
            }
        }).toString()
    }

    private val subs = ConcurrentHashMap<String, Sub>()
    private val pendingOk = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private inner class Connection(val url: String) : WebSocketListener() {
        @Volatile var socket: WebSocket? = null
        @Volatile var up = false
        private val attempt = AtomicLong()
        @Volatile var closed = false

        fun connect() {
            if (closed) return
            socket = client.newWebSocket(Request.Builder().url(url).build(), this)
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            up = true
            attempt.set(0)
            for (s in subs.values) if (s.open) webSocket.send(s.req())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
            when (msg.getOrNull(0)?.jsonPrimitive?.content) {
                "EVENT" -> {
                    val sub = subs[msg.getOrNull(1)?.jsonPrimitive?.content ?: return] ?: return
                    val event = runCatching { NostrEvent.fromJson(msg[2]) }.getOrNull() ?: return
                    if (sub.open && sub.filters.any { it.matches(event) } && sub.seen.add(event.id)) sub.onEvent(event)
                }
                "EOSE" -> {
                    val sub = subs[msg.getOrNull(1)?.jsonPrimitive?.content ?: return] ?: return
                    sub.eosed += url
                    if (connections.values.filter { it.up }.all { it.url in sub.eosed }) sub.reportEose()
                }
                "OK" -> {
                    val id = msg.getOrNull(1)?.jsonPrimitive?.content ?: return
                    val ok = msg.getOrNull(2)?.jsonPrimitive?.content == "true"
                    if (ok) pendingOk[id]?.complete(true)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = retry()
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = retry()

        private fun retry() {
            up = false
            if (closed) return
            val n = attempt.incrementAndGet()
            scope.launch {
                delay(minOf(30_000L, 500L * (1L shl minOf(n.toInt(), 6))))
                connect()
            }
        }

        fun close() { closed = true; socket?.close(1000, null) }
    }

    private val connections = urls.associateWith { Connection(it) }.also { m -> m.values.forEach { it.connect() } }

    override suspend fun publish(event: NostrEvent) {
        val ack = pendingOk.getOrPut(event.id) { CompletableDeferred() }
        val frame = JsonArray(listOf(JsonPrimitive("EVENT"), event.toJson())).toString()
        try {
            val accepted = withTimeoutOrNull(publishTimeoutMs) {
                // A relay still connecting is waited for, briefly, rather than skipped.
                while (connections.values.none { it.up }) delay(50)
                connections.values.filter { it.up }.forEach { it.socket?.send(frame) }
                ack.await()
            }
            if (accepted != true) throw RelayRefused("No relay accepted the event.")
        } finally {
            pendingOk.remove(event.id)
        }
    }

    override fun subscribe(filters: List<Filter>, onEvent: (NostrEvent) -> Unit, onEose: (() -> Unit)?): () -> Unit {
        val sub = Sub("k${nextSub.incrementAndGet()}", filters, onEvent, onEose)
        subs[sub.id] = sub
        for (c in connections.values) if (c.up) c.socket?.send(sub.req())
        scope.launch { delay(eoseTimeoutMs); sub.reportEose() }
        return {
            sub.open = false
            subs.remove(sub.id)
            val close = JsonArray(listOf(JsonPrimitive("CLOSE"), JsonPrimitive(sub.id))).toString()
            for (c in connections.values) if (c.up) c.socket?.send(close)
        }
    }

    fun close() {
        connections.values.forEach { it.close() }
        scope.cancel()
    }
}

class RelayRefused(message: String) : IllegalStateException(message)
