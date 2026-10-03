package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable

/** The app-wide audio session, driven by the coordinator (§14). */
internal fun interface AudioSessionPolicy {
    /** [audible]: an unmuted player is playing. Otherwise audio must mix with other apps. */
    fun onAudibleChanged(audible: Boolean)
}

internal expect fun createAudioSessionPolicy(): AudioSessionPolicy

/** Keeps the screen on while composed with [enabled]; the previous behavior returns once nothing requests it. */
@Composable
internal expect fun KeepScreenAwake(enabled: Boolean)
