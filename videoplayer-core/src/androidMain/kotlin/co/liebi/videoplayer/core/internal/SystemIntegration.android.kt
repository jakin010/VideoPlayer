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

internal actual fun createSystemIntegration(): SystemIntegration = CoordinatorAudio(ApplicationContext.get())

/**
 * One coordinator's vote. Audio focus is app-wide in practice: two requests from one app take focus from each
 * other, so all coordinators share [SharedAudioFocus], like the iOS audio session.
 */
private class CoordinatorAudio(private val context: Context) : SystemIntegration {
    var isAudible = false
        private set

    override fun start(events: SystemEvents) {
        SystemListeners.add(events)
        SharedAudioFocus.add(this, context)
    }

    override fun onAudibleChanged(audible: Boolean) {
        isAudible = audible
        SharedAudioFocus.apply()
    }
}

/**
 * One audio focus request for the whole app, held while any coordinator has an unmuted player playing. Muted
 * players never request focus, so they never interrupt other apps. Players don't use Media3's focus handling:
 * several players would otherwise take focus from each other.
 */
private object SharedAudioFocus {
    // Weak, so a coordinator the app dropped no longer counts.
    private val votes = mutableListOf<WeakReference<CoordinatorAudio>>()
    private lateinit var context: Context
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private var hasFocus = false

    /** A transient loss is in progress. Focus is kept so the system can return it. */
    private var transientLoss = false
    private var noisyRegistered = false

    private val isAudible: Boolean
        get() {
            votes.removeAll { it.get() == null }
            return votes.any { it.get()?.isAudible == true }
        }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (transientLoss) {
                transientLoss = false
                SystemListeners.dispatch { it.onInterruptionEnded(shouldResume = true) }
                if (!isAudible) abandonFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                transientLoss = true
                SystemListeners.dispatch { it.onInterruptionBegan(includesMuted = false) }
            }
            // Since Android 8 the system lowers the volume itself and playback continues.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
            AudioManager.AUDIOFOCUS_LOSS -> {
                transientLoss = false
                abandonFocus()
                SystemListeners.dispatch { it.onAudioLost() }
            }
        }
    }

    private val focusRequest: AudioFocusRequest? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) buildFocusRequest() else null
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) SystemListeners.dispatch { it.onAudioLost() }
        }
    }

    fun add(vote: CoordinatorAudio, context: Context) {
        this.context = context.applicationContext
        votes += WeakReference(vote)
    }

    /** Requests or abandons focus for the combined vote of all coordinators. */
    fun apply() {
        if (isAudible) {
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
            handler.post { SystemListeners.dispatch { it.onInterruptionEnded(shouldResume = true) } }
        } else if (!hasFocus) {
            // Denied, for example during a call. Posted because this runs inside a player update.
            handler.post { if (isAudible && !hasFocus) SystemListeners.dispatch { it.onAudioLost() } }
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

/** Every coordinator's [SystemEvents], held weakly so a coordinator the app dropped is not kept alive. */
private object SystemListeners : DefaultLifecycleObserver {
    private val listeners = mutableListOf<WeakReference<SystemEvents>>()
    private var observingLifecycle = false

    fun add(events: SystemEvents) {
        listeners += WeakReference(events)
        if (observingLifecycle) return
        observingLifecycle = true
        // Process-wide foreground state. ProcessLifecycleOwner ignores configuration changes such as rotation.
        val observe = { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
        if (Looper.myLooper() == Looper.getMainLooper()) observe() else Handler(Looper.getMainLooper()).post(observe)
    }

    override fun onStart(owner: LifecycleOwner) = dispatch { it.onForeground() }

    override fun onStop(owner: LifecycleOwner) = dispatch { it.onBackground() }

    inline fun dispatch(block: (SystemEvents) -> Unit) {
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
