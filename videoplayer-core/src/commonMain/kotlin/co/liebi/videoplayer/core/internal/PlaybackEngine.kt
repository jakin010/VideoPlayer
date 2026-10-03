package co.liebi.videoplayer.core.internal

import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.BufferingConfig
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.PlayerError
import kotlin.time.Duration

/**
 * The thin per-platform playback layer. It loads one item at a time and reports raw facts;
 * all player behavior (status derivation, play intent, retries, events) lives in [DefaultPlayerController].
 *
 * Engines apply the start gating from [BufferingConfig] themselves, because Media3 does it natively.
 * Listener callbacks are delivered on the main thread and may arrive synchronously from within a command.
 */
internal interface PlaybackEngine {
    fun setListener(listener: EngineListener?)

    /** Loads [source] and starts buffering at [startPosition], replacing any loaded item. */
    fun load(source: MediaSource, startPosition: Duration)

    /** Stops and unloads the current item. */
    fun unload()

    /** Whether to play once ready. The engine never plays before it reports [EngineStatus.Ready]. */
    fun setPlayWhenReady(playWhenReady: Boolean)

    /** Seeks precisely. Every call is followed by [EngineListener.onSeekCompleted] unless an error or a new load intervenes. */
    fun seekTo(position: Duration)

    /** Effective gain, already 0 when muted. */
    fun setVolume(volume: Float)

    fun setPlaybackSpeed(speed: Float)

    /**
     * A parked engine keeps its prepared item and buffer but stays paused and stops downloading more,
     * so a recently played item can be switched back to instantly. Unparking resumes loading.
     */
    fun setParked(parked: Boolean)

    val currentPosition: Duration

    /** End of the contiguous buffered range that contains [currentPosition]. */
    val bufferedPosition: Duration

    fun release()
}

internal interface EngineListener {
    fun onStatusChanged(status: EngineStatus)
    fun onTimelineChanged(timeline: EngineTimeline)
    fun onSeekCompleted()
    fun onFirstFrameRendered()
    fun onVideoSizeChanged(size: IntSize)
    fun onError(error: PlayerError)
}

internal enum class EngineStatus {
    Idle,

    /** Loading, seeking or stalled: not enough media buffered to play. */
    Buffering,
    Ready,
    Ended,
}

internal data class EngineTimeline(
    val duration: Duration?,
    val isLive: Boolean,
    val isSeekable: Boolean,
)

internal expect fun createPlatformEngine(buffering: BufferingConfig): PlaybackEngine

internal expect fun logWarning(message: String)
