package co.liebi.videoplayer.sample

import androidx.compose.ui.window.ComposeUIViewController
import co.liebi.videoplayer.ui.FullscreenStatusBar

fun MainViewController() = ComposeUIViewController { App() }

/** For Swift: the status bar hides while a player is fullscreen. Compose can't hide it itself. */
fun observeFullscreenStatusBar(onChange: (Boolean) -> Unit) {
    FullscreenStatusBar.observe(onChange)
}
