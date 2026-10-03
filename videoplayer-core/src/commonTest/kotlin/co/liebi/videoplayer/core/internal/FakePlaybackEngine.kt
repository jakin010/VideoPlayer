package co.liebi.videoplayer.core.internal

import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.PlayerError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Scriptable engine: records commands, and tests drive the callbacks. Callbacks are synchronous, like Media3's. */
internal class FakePlaybackEngine : PlaybackEngine {
    var listener: EngineListener? = null
        private set

    val loads = mutableListOf<Pair<MediaSource, Duration>>()
    val seeks = mutableListOf<Duration>()
    var playWhenReady = false
        private set
    var volume = 1f
        private set
    var speed = 1f
        private set
    var unloadCount = 0
        private set
    var isReleased = false
        private set
    var isParked = false
        private set

    override var currentPosition: Duration = Duration.ZERO
    override var bufferedPosition: Duration = Duration.ZERO

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    override fun load(source: MediaSource, startPosition: Duration) {
        loads += source to startPosition
        currentPosition = startPosition
        listener?.onStatusChanged(EngineStatus.Buffering)
    }

    override fun unload() {
        unloadCount++
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
    }

    override fun seekTo(position: Duration) {
        seeks += position
        listener?.onStatusChanged(EngineStatus.Buffering)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
    }

    override fun setParked(parked: Boolean) {
        isParked = parked
        if (parked) playWhenReady = false
    }

    override fun release() {
        isReleased = true
    }

    // Test drivers

    fun becomeReady(duration: Duration? = 60.seconds, isSeekable: Boolean = true, isLive: Boolean = false) {
        listener?.onTimelineChanged(EngineTimeline(duration, isLive = isLive, isSeekable = isSeekable))
        listener?.onStatusChanged(EngineStatus.Ready)
    }

    fun stall() = listener?.onStatusChanged(EngineStatus.Buffering)

    fun resumeFromStall() = listener?.onStatusChanged(EngineStatus.Ready)

    /** Completes the latest seek and reports Ready, as engines do. */
    fun completeSeek() {
        currentPosition = seeks.last()
        listener?.onSeekCompleted()
        listener?.onStatusChanged(EngineStatus.Ready)
    }

    fun end() {
        listener?.onStatusChanged(EngineStatus.Ended)
    }

    fun fail(error: PlayerError) {
        listener?.onError(error)
    }

    fun renderFirstFrame() = listener?.onFirstFrameRendered()

    fun reportVideoSize(size: IntSize) = listener?.onVideoSizeChanged(size)
}
