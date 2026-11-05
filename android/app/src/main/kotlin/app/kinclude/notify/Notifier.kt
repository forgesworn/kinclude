package app.kinclude.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import app.kinclude.MainActivity
import app.kinclude.R
import app.kinclude.core.Household
import app.kinclude.core.RoomView

/**
 * What reaches the phone's notification shade, without any push service:
 * the relays are kept open by [KincludeService], and every change in the
 * household is looked at here.
 *
 * - **Messages**, one conversation-style notification per room, as
 *   WhatsApp and Signal show them; not for the room on screen.
 * - **Calls** ring: a full-screen incoming-call notification on the lock
 *   screen, when somebody starts or is on a call this phone is not on.
 * - **An emergency opening** of a child's messages notifies that child at
 *   once, on its own channel. Nothing in Kinclude turns it off.
 */
class Notifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)
    private val prefs = context.getSharedPreferences("kinclude.notified", Context.MODE_PRIVATE)

    /** The room on screen, which gets no notification. */
    @Volatile var visibleRoom: String? = null

    init { channels() }

    private fun channels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        system.createNotificationChannel(NotificationChannel(CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        })
        system.createNotificationChannel(NotificationChannel(OVERSIGHT, "Oversight notices", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a guardian opens your messages in an emergency. Kinclude always tells you."
        })
        system.createNotificationChannel(NotificationChannel(WAITING, "Invitations and approvals", NotificationManager.IMPORTANCE_DEFAULT))
        system.createNotificationChannel(NotificationChannel(SERVICE, "Staying connected", NotificationManager.IMPORTANCE_MIN))
    }

    private fun allowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(roomId: String?, call: Boolean, request: Int): PendingIntent = PendingIntent.getActivity(
        context, request,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_CALL, call),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Look at the household and notify what is new. Safe to call often. */
    fun check(h: Household, views: Map<String, RoomView>, callOn: (String) -> Boolean, now: Long) {
        if (!allowed()) return
        val names = { pk: String -> nameIn(h, views.values, pk) }
        for ((roomId, v) in views) {
            messages(roomId, v, h, names)
            call(roomId, v, h, names, callOn(roomId), now)
            emergencies(roomId, v, h, names)
        }
        waiting(h)
    }

    @Suppress("MissingPermission")
    private fun messages(roomId: String, v: RoomView, h: Household, names: (String) -> String) {
        val key = "msg.$roomId"
        val since = prefs.getLong(key, -1)
        val newest = v.messages.maxOfOrNull { it.sentAt } ?: return
        if (since < 0) { prefs.edit().putLong(key, newest).apply(); return } // a room just opened: nothing old rings
        if (roomId == visibleRoom) { prefs.edit().putLong(key, newest).apply(); manager.cancel(roomId, ID_MESSAGES); return }
        val fresh = v.messages.filter { it.sentAt > since && it.from != h.me }
        if (fresh.isEmpty()) return
        prefs.edit().putLong(key, newest).apply()
        val me = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(me).setConversationTitle(v.name).setGroupConversation(true)
        v.messages.filter { it.from != h.me }.takeLast(6).forEach {
            style.addMessage(it.text, it.sentAt * 1000, Person.Builder().setName(it.name ?: names(it.from)).build())
        }
        manager.notify(roomId, ID_MESSAGES, NotificationCompat.Builder(context, MESSAGES)
            .setSmallIcon(R.drawable.ic_notification).setStyle(style).setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE).setContentIntent(open(roomId, false, roomId.hashCode()))
            .build())
    }

    @Suppress("MissingPermission")
    private fun call(roomId: String, v: RoomView, h: Household, names: (String) -> String, onIt: Boolean, now: Long) {
        val c = v.call
        val others = c?.on?.filter { it.participant != h.me }.orEmpty()
        if (c == null || others.isEmpty() || onIt || !v.reading) { manager.cancel(roomId, ID_CALL); return }
        val started = others.minOf { it.at }
        // Ring for a call that began in the last minute, once per call.
        if (now - started > 60 || prefs.getString("call.$roomId", null) == c.id) return
        prefs.edit().putString("call.$roomId", c.id).apply()
        val caller = Person.Builder().setName(names(others.first().participant)).setImportant(true).build()
        val answer = open(roomId, true, roomId.hashCode() + 1)
        val decline = PendingIntent.getBroadcast(context, roomId.hashCode() + 2,
            Intent(context, DeclineReceiver::class.java).putExtra(EXTRA_ROOM, roomId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(roomId, ID_CALL, NotificationCompat.Builder(context, CALLS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(v.name)
            .setContentText("${list(others.map { names(it.participant) })} ${if (others.any { it.video }) "video" else "voice"} call")
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(answer, true)
            .setOngoing(true)
            .setTimeoutAfter(45_000)
            .build())
    }

    @Suppress("MissingPermission")
    private fun emergencies(roomId: String, v: RoomView, h: Household, names: (String) -> String) {
        for (e in v.emergencies) {
            if (e.notice.child != h.me) continue
            val key = "emergency.${e.event.id}"
            if (prefs.getBoolean(key, false)) continue
            prefs.edit().putBoolean(key, true).apply()
            manager.notify(e.event.id, ID_EMERGENCY, NotificationCompat.Builder(context, OVERSIGHT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${names(e.signer)} opened your messages in ${v.name}")
                .setContentText(e.notice.reason ?: "In an emergency.")
                .setStyle(NotificationCompat.BigTextStyle().bigText(e.notice.reason ?: "In an emergency."))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(open(roomId, false, e.event.id.hashCode()))
                .build())
        }
    }

    @Suppress("MissingPermission")
    private fun waiting(h: Household) {
        val count = h.data.invites.size + h.pendingTiers().size
        val key = "waiting.count"
        val before = prefs.getInt(key, 0)
        prefs.edit().putInt(key, count).apply()
        if (count == 0) { manager.cancel(ID_WAITING); return }
        if (count <= before) return
        manager.notify(ID_WAITING, NotificationCompat.Builder(context, WAITING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Kinclude")
            .setContentText(if (count == 1) "Something is waiting for you." else "$count things are waiting for you.")
            .setAutoCancel(true)
            .setContentIntent(open(null, false, ID_WAITING))
            .build())
    }

    fun serviceNotification() = NotificationCompat.Builder(context, SERVICE)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Kinclude is keeping your family's chats arriving")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setContentIntent(open(null, false, 0))
        .build()

    fun cancelCall(roomId: String) = manager.cancel(roomId, ID_CALL)

    companion object {
        const val MESSAGES = "messages"
        const val CALLS = "calls"
        const val OVERSIGHT = "oversight"
        const val WAITING = "waiting"
        const val SERVICE = "service"
        const val EXTRA_ROOM = "app.kinclude.room"
        const val EXTRA_CALL = "app.kinclude.call"
        const val ID_MESSAGES = 1
        const val ID_CALL = 2
        const val ID_EMERGENCY = 3
        const val ID_WAITING = 4
        const val ID_SERVICE = 5

        private fun list(names: List<String>) = if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()

        private fun nameIn(h: Household, views: Collection<RoomView>, pk: String): String {
            if (pk == h.me) return h.data.name
            h.data.children.firstOrNull { it.pk == pk }?.name?.let { return it }
            h.data.guardians.firstOrNull { it.pk == pk }?.name?.let { return it }
            h.data.contacts.firstOrNull { it.pk == pk }?.name?.let { return it }
            for (v in views) v.state?.member(pk)?.name?.let { return it }
            return "Someone"
        }
    }
}
