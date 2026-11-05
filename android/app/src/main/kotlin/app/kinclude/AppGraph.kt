package app.kinclude

import android.content.Context
import app.kinclude.core.Household
import app.kinclude.core.KincludeRoom
import app.kinclude.core.RoomCall
import app.kinclude.data.FileHouseholdStore
import app.kinclude.data.IdentityStore
import app.kinclude.net.RelayPool
import app.kinclude.call.WebRtcCalls
import app.kinclude.notify.KincludeService
import app.kinclude.notify.Notifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Everything the app holds for its whole life: who this phone is, its
 * relays, its household, and the call in progress. Screens read [tick] to
 * know when to look again.
 */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val identities = IdentityStore(context)
    private val store = FileHouseholdStore(context)
    private val prefs = context.getSharedPreferences("kinclude", Context.MODE_PRIVATE)

    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick.asStateFlow()
    val notifier = Notifier(context)
    private var notifying: Job? = null

    fun changed() {
        _tick.value = _tick.value + 1
        // Notifications look at the household a moment after it settles.
        if (notifying?.isActive == true) return
        notifying = scope.launch {
            delay(400)
            val h = _household.value ?: return@launch
            runCatching {
                notifier.check(h, h.rooms.mapValues { it.value.view() }, { id -> roomCalls[id]?.status != null && roomCalls[id]?.status != RoomCall.Status.IDLE }, System.currentTimeMillis() / 1000)
            }
        }
    }

    /** Where a tapped notification asked to go: a room, and whether to its call. */
    private val _opening = MutableStateFlow<Pair<String, Boolean>?>(null)
    val opening: StateFlow<Pair<String, Boolean>?> = _opening.asStateFlow()
    fun openFromNotification(roomId: String?, call: Boolean) { if (roomId != null) _opening.value = roomId to call }
    fun opened() { _opening.value = null }

    private val _household = MutableStateFlow<Household?>(null)
    val household: StateFlow<Household?> = _household.asStateFlow()
    private val _starting = MutableStateFlow(true)
    val starting: StateFlow<Boolean> = _starting.asStateFlow()

    var relays: List<String>
        get() = prefs.getString("relays", null)?.split('\n')?.filter { it.startsWith("ws") }?.takeIf { it.isNotEmpty() } ?: DEFAULT_RELAYS
        set(value) { prefs.edit().putString("relays", value.joinToString("\n")).apply() }

    var iceServers: List<String>
        get() = prefs.getString("ice", null)?.split('\n')?.filter { it.isNotBlank() } ?: DEFAULT_ICE
        set(value) { prefs.edit().putString("ice", value.joinToString("\n")).apply() }

    /** The newest message this phone has shown in each room: what "unread" counts from. */
    fun lastRead(roomId: String): Long = prefs.getLong("read.$roomId", 0)
    fun markRead(roomId: String, sentAt: Long) {
        if (sentAt > lastRead(roomId)) { prefs.edit().putLong("read.$roomId", sentAt).apply(); changed() }
    }

    private var pool: RelayPool? = null
    val calls by lazy { WebRtcCalls(context) }
    private val roomCalls = HashMap<String, RoomCall>()

    fun start() {
        scope.launch {
            val saved = identities.load()
            if (saved != null) open(saved)
            _starting.value = false
        }
    }

    private suspend fun open(saved: IdentityStore.Saved) {
        val p = RelayPool(relays).also { pool = it }
        val h = Household.open(p, identities.signer(saved), store, saved.name, saved.child, onChange = { changed() })
        h.start()
        _household.value = h
        KincludeService.start(context)
        changed()
    }

    fun setUp(child: Boolean, name: String) {
        scope.launch {
            val saved = identities.create(child, name)
            open(saved)
        }
    }

    fun forget() {
        roomCalls.values.forEach { c -> scope.launch { c.close() } }
        roomCalls.clear()
        _household.value?.close()
        KincludeService.stop(context)
        pool?.close()
        identities.delete()
        store.delete()
        _household.value = null
        changed()
    }

    fun callFor(room: KincludeRoom): RoomCall = roomCalls.getOrPut(room.roomId) {
        RoomCall(room, calls, iceServers.map { WebRtcCalls.ice(it) }, onChange = { changed() }).also { it.watch() }
    }

    fun activeCall(): Pair<KincludeRoom, RoomCall>? {
        val h = _household.value ?: return null
        return roomCalls.entries.firstNotNullOfOrNull { (id, c) ->
            val room = h.rooms[id]
            if (room != null && c.status != RoomCall.Status.IDLE) room to c else null
        }
    }

    companion object {
        val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol")
        val DEFAULT_ICE = listOf("stun:stun.cloudflare.com:3478")
    }
}
