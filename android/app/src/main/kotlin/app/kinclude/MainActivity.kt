package app.kinclude

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.kinclude.ui.KincludeApp
import app.kinclude.ui.KincludeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as KincludeApplication).graph
        setContent { KincludeTheme { KincludeApp(graph) } }
    }
}
