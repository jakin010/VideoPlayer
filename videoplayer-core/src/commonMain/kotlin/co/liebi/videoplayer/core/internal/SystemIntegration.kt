package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable

/**
 * App-wide audio and lifecycle integration, owned by the coordinator (§13, §14): the iOS audio session or the
 * Android audio focus request, interruptions, and the app moving to the background.
 */
internal fun interface SystemIntegration {
    /**
     * [audible]: an unmuted player wants to play, so the app needs the playback session or audio focus.
     * Otherwise audio must mix with other apps and hold no focus. Only called when the coordinator manages audio.
     */
    fun onAudibleChanged(audible: Boolean)

    /** Starts delivering system events to [events]. Called once by the coordinator, which keeps [events] alive. */
    fun start(events: SystemEvents) {}
}

/** System events, delivered on the main thread. */
internal interface SystemEvents {
    /**
     * A temporary interruption began: a call, Siri, an alarm or a transient loss of audio focus.
     * [includesMuted]: the system stopped muted players too (iOS); otherwise only audible players are affected.
     */
    fun onInterruptionBegan(includesMuted: Boolean)

    /** The interruption ended. [shouldResume]: the system allows playback to continue. */
    fun onInterruptionEnded(shouldResume: Boolean)

    /** Audio output was lost for good: another media app took audio focus, or headphones were disconnected. */
    fun onAudioLost()

    fun onBackground()

    fun onForeground()
}

internal expect fun createSystemIntegration(): SystemIntegration

/** Keeps the screen on while composed with [enabled]; the previous behavior returns once nothing requests it. */
@Composable
internal expect fun KeepScreenAwake(enabled: Boolean)
