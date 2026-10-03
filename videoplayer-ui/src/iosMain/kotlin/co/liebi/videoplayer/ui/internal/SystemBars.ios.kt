package co.liebi.videoplayer.ui.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import co.liebi.videoplayer.ui.FullscreenStatusBar

@Composable
internal actual fun HideSystemBars() {
    DisposableEffect(Unit) {
        FullscreenStatusBar.acquire()
        onDispose { FullscreenStatusBar.release() }
    }
}
