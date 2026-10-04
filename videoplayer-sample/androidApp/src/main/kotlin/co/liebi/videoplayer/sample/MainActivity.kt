package co.liebi.videoplayer.sample

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import co.liebi.videoplayer.sample.checks.ChecksHooks

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The app draws its own light and dark themes; stop the system (and One UI) from recoloring it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.decorView.isForceDarkAllowed = false

        // `adb -s <emulator> shell am start -n co.liebi.videoplayer.sample/.MainActivity --es checks all` runs the checks.
        ChecksHooks.autoRun = intent.getStringExtra("checks")
        installLeakWatcher()

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}