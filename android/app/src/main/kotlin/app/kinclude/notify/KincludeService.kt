package app.kinclude.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.kinclude.KincludeApplication

/**
 * Keeps the app's relay connections open while it is in the background, so
 * messages and calls arrive with no push service in between. The household
 * itself lives in the application ([app.kinclude.AppGraph]); this service
 * only keeps the process alive, with the quietest notification Android
 * allows. KithMoot's `BackgroundCallListenerService` is the pattern.
 */
class KincludeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as KincludeApplication).graph
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.ID_SERVICE, graph.notifier.serviceNotification(), type)
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, KincludeService::class.java)) }
        }
        fun stop(context: Context) { context.stopService(Intent(context, KincludeService::class.java)) }
    }
}

/** "Decline" on a ringing call: stop ringing on this phone. */
class DeclineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val room = intent.getStringExtra(Notifier.EXTRA_ROOM) ?: return
        (context.applicationContext as KincludeApplication).graph.notifier.cancelCall(room)
    }
}

/** Start listening again after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) KincludeService.start(context)
    }
}
