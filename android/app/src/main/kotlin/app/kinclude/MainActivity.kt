package app.kinclude

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.kinclude.data.SignerBridge
import kotlinx.coroutines.CompletableDeferred
import app.kinclude.notify.Notifier
import app.kinclude.ui.KincludeApp
import app.kinclude.ui.KincludeTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val graph get() = (application as KincludeApplication).graph

    // A signer app's screen, opened for a signature and answered back here.
    private var pending: CompletableDeferred<Intent?>? = null
    private val signerScreen = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.complete(if (result.resultCode == RESULT_OK) result.data else null)
        pending = null
    }
    private val bridge = SignerBridge { intent ->
        val answer = CompletableDeferred<Intent?>()
        runOnUiThread { pending?.complete(null); pending = answer; runCatching { signerScreen.launch(intent) }.onFailure { answer.complete(null) } }
        answer.await()
    }

    override fun onResume() {
        super.onResume()
        graph.signerBridge = bridge
    }

    override fun onPause() {
        if (graph.signerBridge === bridge) graph.signerBridge = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        // Messages and calls notify only with this permission (Android 13 and later).
        if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { KincludeTheme { KincludeApp(graph) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        graph.openFromNotification(intent?.getStringExtra(Notifier.EXTRA_ROOM), intent?.getBooleanExtra(Notifier.EXTRA_CALL, false) == true)
    }
}
