package co.liebi.videoplayer.core.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.lang.ref.WeakReference

internal actual fun createSystemIntegration(): SystemIntegration = AndroidSystemIntegration(ApplicationContext.get())

/**
 * One audio focus request per coordinator, held while an unmuted player plays. Muted players never request
 * focus, so they never interrupt other apps. Players don't use Media3's focus handling: several players of
 * one coordinator would otherwise take focus from each other.
 */
private class AndroidSystemIntegration(private val context: Context) : SystemIntegration {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var events: SystemEvents? = null
    private var audible = false
    private var hasFocus = false

    /** A transient loss is in progress. Focus is kept so the system can return it. */
    private var transientLoss = false
    private var noisyRegistered = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (transientLoss) {
                transientLoss = false
                events?.onInterruptionEnded(shouldResume = true)
                if (!audible) abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                transientLoss = true
                events?.onInterruptionBegan(includesMuted = false)
            }
            // Since Android 8 the system lowers the volume itself and playback continues.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            AudioManager.AUDIOFOCUS_LOSS -> {
                transientLoss = false
                abandonFocus()
                events?.onAudioLost()
            }
        }
    }

    private val focusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) buildFocusRequest() else null
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) events?.onAudioLost()
        }
    }

    override fun start(events: SystemEvents) {
        this.events = events
        AppLifecycle.add(events)
    }

    override fun onAudibleChanged(audible: Boolean) {
        this.audible = audible
        if (audible) {
            registerNoisyReceiver()
            // Asking again during a transient loss tells whether the interruption is still going on.
            if (!hasFocus || transientLoss) requestFocus()
        } else {
            unregisterNoisyReceiver()
            if (!transientLoss) abandonFocus()
        }
    }

    private fun requestFocus() {
        val result = focusRequest?.let { audioManager.requestAudioFocus(it) }
            ?: @Suppress("DEPRECATION") audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus && transientLoss) {
            transientLoss = false
            handler.post { events?.onInterruptionEnded(shouldResume = true) }
        } else if (!hasFocus) {
            // Denied, for example during a call. Posted because this runs inside a player update.
            handler.post { if (audible && !hasFocus) events?.onAudioLost() }
        }
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        hasFocus = false
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            ?: @Suppress("DEPRECATION") audioManager.abandonAudioFocus(focusListener)
    }

    private fun registerNoisyReceiver() {
        if (noisyRegistered) return
        noisyRegistered = true
        // A protected system broadcast, so no export flag is needed.
        context.registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyRegistered) return
        noisyRegistered = false
        context.unregisterReceiver(noisyReceiver)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildFocusRequest(): AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        .setOnAudioFocusChangeListener(focusListener, handler)
        .build()
}

/** Process-wide foreground state. ProcessLifecycleOwner ignores configuration changes such as rotation. */
private object AppLifecycle : DefaultLifecycleObserver {
    // Weak, so a coordinator the app dropped is not kept alive.
    private val listeners = mutableListOf<WeakReference<SystemEvents>>()
    private var observing = false

    fun add(events: SystemEvents) {
        listeners += WeakReference(events)
        if (observing) return
        observing = true
        val observe = { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
        if (Looper.myLooper() == Looper.getMainLooper()) observe() else Handler(Looper.getMainLooper()).post(observe)
    }

    override fun onStart(owner: LifecycleOwner) = dispatch { it.onForeground() }

    override fun onStop(owner: LifecycleOwner) = dispatch { it.onBackground() }

    private inline fun dispatch(block: (SystemEvents) -> Unit) {
        listeners.removeAll { it.get() == null }
        listeners.mapNotNull { it.get() }.forEach(block)
    }
}

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
