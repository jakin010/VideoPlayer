package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.UIKit.UIApplication

internal actual fun createAudioSessionPolicy(): AudioSessionPolicy = CoordinatorAudio()

/** One coordinator's vote. The app has a single audio session, so votes of all coordinators are combined. */
private class CoordinatorAudio : AudioSessionPolicy {
    private var audible = false

    override fun onAudibleChanged(audible: Boolean) {
        if (audible != this.audible) {
            this.audible = audible
            SharedAudioSession.audibleCoordinators += if (audible) 1 else -1
        }
        SharedAudioSession.apply()
    }
}

/**
 * Ambient while nothing audible plays: mixes with other apps and respects the silent switch, so muted
 * autoplay never stops the user's music. Playback (active) while an unmuted player plays.
 */
@OptIn(ExperimentalForeignApi::class)
private object SharedAudioSession {
    var audibleCoordinators = 0
    private var applied: Boolean? = null

    fun apply() {
        val audible = audibleCoordinators > 0
        if (audible == applied) return
        applied = audible
        val session = AVAudioSession.sharedInstance()
        if (audible) {
            session.setCategory(AVAudioSessionCategoryPlayback, error = null)
            session.setActive(true, error = null)
        } else {
            // Lets interrupted apps resume. Fails harmlessly while a muted player still runs audio output.
            session.setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)
            session.setCategory(AVAudioSessionCategoryAmbient, error = null)
        }
    }
}

@Composable
internal actual fun KeepScreenAwake(enabled: Boolean) {
    if (!enabled) return
    DisposableEffect(Unit) {
        IdleTimer.acquire()
        onDispose { IdleTimer.release() }
    }
}

/** The idle timer is app-wide: count requests and restore the app's own value when the last one ends. */
private object IdleTimer {
    private var count = 0
    private var previous = false

    fun acquire() {
        if (count++ == 0) {
            previous = UIApplication.sharedApplication.idleTimerDisabled
            UIApplication.sharedApplication.idleTimerDisabled = true
        }
    }

    fun release() {
        if (count > 0 && --count == 0) UIApplication.sharedApplication.idleTimerDisabled = previous
    }
}
