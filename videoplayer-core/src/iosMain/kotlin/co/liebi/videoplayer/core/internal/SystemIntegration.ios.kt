package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionInterruptionWasSuspendedKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference

internal actual fun createSystemIntegration(): SystemIntegration = CoordinatorAudio()

/** One coordinator's vote. The app has a single audio session, so votes of all coordinators are combined. */
private class CoordinatorAudio : SystemIntegration {
    private var audible = false

    override fun onAudibleChanged(audible: Boolean) {
        if (audible != this.audible) {
            this.audible = audible
            SharedAudioSession.audibleCoordinators += if (audible) 1 else -1
        }
        SharedAudioSession.apply()
    }

    override fun start(events: SystemEvents) {
        SystemNotifications.add(events)
    }
}

/**
 * Ambient while nothing audible plays: mixes with other apps and respects the silent switch, so muted
 * autoplay never stops the user's music. Playback (active) while an unmuted player plays.
 */
@OptIn(ExperimentalForeignApi::class)
private object SharedAudioSession {
    var audibleCoordinators = 0

    /** The category last applied; `null` forces the next [apply], for example after an interruption. */
    var applied: Boolean? = null

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

/** App-wide notifications, forwarded to every coordinator. Interruptions stop all players, muted ones too. */
@OptIn(ExperimentalNativeApi::class)
private object SystemNotifications {
    // Weak, so a coordinator the app dropped is not kept alive.
    private val listeners = mutableListOf<WeakReference<SystemEvents>>()

    init {
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        center.addObserverForName(AVAudioSessionInterruptionNotification, null, main) { onInterruption(it) }
        center.addObserverForName(AVAudioSessionRouteChangeNotification, null, main) { onRouteChange(it) }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, main) { _ -> dispatch { it.onBackground() } }
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, main) { _ -> dispatch { it.onForeground() } }
    }

    fun add(events: SystemEvents) {
        listeners += WeakReference(events)
    }

    private fun onInterruption(notification: NSNotification?) {
        val info = notification?.userInfo ?: return
        when ((info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedIntegerValue) {
            AVAudioSessionInterruptionTypeBegan -> {
                // Delivered late for an app that was suspended; its players were already paused for the background.
                if ((info[AVAudioSessionInterruptionWasSuspendedKey] as? NSNumber)?.boolValue == true) return
                // The system deactivated the session, so the next apply must activate it again.
                SharedAudioSession.applied = null
                dispatch { it.onInterruptionBegan(includesMuted = true) }
            }
            AVAudioSessionInterruptionTypeEnded -> {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedIntegerValue ?: 0u
                SharedAudioSession.applied = null
                dispatch { it.onInterruptionEnded(shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL) }
            }
        }
    }

    private fun onRouteChange(notification: NSNotification?) {
        val reason = (notification?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedIntegerValue
        // Headphones or a Bluetooth device went away.
        if (reason == AVAudioSessionRouteChangeReasonOldDeviceUnavailable) dispatch { it.onAudioLost() }
    }

    private inline fun dispatch(block: (SystemEvents) -> Unit) {
        listeners.removeAll { it.value == null }
        listeners.mapNotNull { it.value }.forEach(block)
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
