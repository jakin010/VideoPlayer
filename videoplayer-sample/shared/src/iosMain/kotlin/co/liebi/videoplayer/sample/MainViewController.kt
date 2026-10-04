package co.liebi.videoplayer.sample

import androidx.compose.ui.window.ComposeUIViewController
import co.liebi.videoplayer.sample.checks.ChecksHooks
import co.liebi.videoplayer.ui.FullscreenStatusBar
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    // `xcrun simctl launch <device> co.liebi.videoplayer.sample -checks all` runs the checks on launch.
    val arguments = NSProcessInfo.processInfo.arguments.map { it.toString() }
    ChecksHooks.autoRun = arguments.indexOf("-checks").takeIf { it >= 0 }?.let { arguments.getOrNull(it + 1) }
    return ComposeUIViewController { App() }
}

/** For Swift: the status bar hides while a player is fullscreen. Compose can't hide it itself. */
fun observeFullscreenStatusBar(onChange: (Boolean) -> Unit) {
    FullscreenStatusBar.observe(onChange)
}
