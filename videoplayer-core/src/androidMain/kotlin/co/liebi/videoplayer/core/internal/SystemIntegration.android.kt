package co.liebi.videoplayer.core.internal

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

// Android has no app-wide session category; muted players never request audio focus.
internal actual fun createAudioSessionPolicy(): AudioSessionPolicy = AudioSessionPolicy { }

@Composable
internal actual fun KeepScreenAwake(enabled: Boolean) {
    if (!enabled) return
    val view = LocalView.current
    DisposableEffect(view) {
        ScreenOnRequests.acquire(view)
        onDispose { ScreenOnRequests.release(view) }
    }
}

/** Several surfaces can share one window view, so requests are counted per view. */
private object ScreenOnRequests {
    private val counts = mutableMapOf<View, Int>()

    fun acquire(view: View) {
        counts[view] = (counts[view] ?: 0) + 1
        view.keepScreenOn = true
    }

    fun release(view: View) {
        val remaining = (counts[view] ?: 1) - 1
        if (remaining > 0) {
            counts[view] = remaining
        } else {
            counts.remove(view)
            view.keepScreenOn = false
        }
    }
}
