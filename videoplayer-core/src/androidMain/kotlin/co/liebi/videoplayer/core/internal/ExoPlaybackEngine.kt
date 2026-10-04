package co.liebi.videoplayer.core.internal

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import co.liebi.videoplayer.core.BufferingConfig
import co.liebi.videoplayer.core.InternalVideoPlayerApi
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.VideoPlayerDiagnostics
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.source.MediaSource as ExoMediaSource

/**
 * Media3 engine. The buffer thresholds map directly onto [DefaultLoadControl], so ExoPlayer applies the
 * start gating itself (§5). One native item is loaded at a time; playlists live in common code (§7).
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(InternalVideoPlayerApi::class)
internal class ExoPlaybackEngine(
    private val context: Context,
    buffering: BufferingConfig,
) : PlaybackEngine {

    private var listener: EngineListener? = null
    private var seekPending = false
    private var lastTimeline: EngineTimeline? = null

    private val playerListener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            reportTimeline()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            reportTimeline()
            when (playbackState) {
                Player.STATE_BUFFERING -> listener?.onStatusChanged(EngineStatus.Buffering)
                Player.STATE_READY -> {
                    completeSeek()
                    listener?.onStatusChanged(EngineStatus.Ready)
                }
                Player.STATE_ENDED -> {
                    completeSeek()
                    listener?.onStatusChanged(EngineStatus.Ended)
                }
                Player.STATE_IDLE -> listener?.onStatusChanged(EngineStatus.Idle)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // A seek that does not leave READY completes immediately.
            if (reason == Player.DISCONTINUITY_REASON_SEEK && player.playbackState == Player.STATE_READY) completeSeek()
        }

        override fun onPlayerError(error: PlaybackException) {
            seekPending = false
            listener?.onError(error.toPlayerError())
        }

        override fun onRenderedFirstFrame() {
            listener?.onFirstFrameRendered()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).roundToInt()
            listener?.onVideoSizeChanged(IntSize(width, videoSize.height))
        }
    }

    private val loadControl = ParkableLoadControl(buffering.toLoadControl())

    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(CodecSelector))
        .setLooper(Looper.getMainLooper())
        .setLoadControl(loadControl)
        .build()
        .also {
            it.addListener(playerListener)
            VideoPlayerDiagnostics.onNativePlayerCreated?.invoke(it)
        }

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    override fun load(source: MediaSource, startPosition: Duration) {
        seekPending = false
        lastTimeline = null
        player.setMediaSource(createMediaSource(source), startPosition.inWholeMilliseconds)
        player.prepare()
    }

    override fun unload() {
        seekPending = false
        lastTimeline = null
        player.stop()
        player.clearMediaItems()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        player.playWhenReady = playWhenReady
    }

    override fun seekTo(position: Duration) {
        seekPending = true
        player.seekTo(position.inWholeMilliseconds)
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    override fun setParked(parked: Boolean) {
        loadControl.isLoadingPaused = parked
        if (parked) player.playWhenReady = false
    }

    override val currentPosition: Duration
        get() = player.currentPosition.milliseconds

    override val bufferedPosition: Duration
        get() = player.bufferedPosition.milliseconds

    override fun release() {
        listener = null
        player.removeListener(playerListener)
        player.release()
    }

    private fun completeSeek() {
        if (!seekPending) return
        seekPending = false
        listener?.onSeekCompleted()
    }

    private fun reportTimeline() {
        if (player.currentTimeline.isEmpty) return
        val timeline = EngineTimeline(
            duration = player.duration.takeIf { it != C.TIME_UNSET }?.milliseconds,
            isLive = player.isCurrentMediaItemLive,
            isSeekable = player.isCurrentMediaItemSeekable,
        )
        if (timeline == lastTimeline) return
        lastTimeline = timeline
        listener?.onTimelineChanged(timeline)
    }

    private fun createMediaSource(source: MediaSource): ExoMediaSource {
        val httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        val uri = when (source) {
            is MediaSource.Url -> {
                // Applies to every request of the item: MP4 ranges, HLS playlists, segments and keys.
                httpFactory.setDefaultRequestProperties(source.requestHeaders())
                Uri.parse(source.url)
            }
            is MediaSource.File -> Uri.fromFile(java.io.File(source.path))
            is MediaSource.Resource -> Uri.parse(source.uri)
        }
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
            .createMediaSource(ExoMediaItem.fromUri(uri))
    }
}

private fun MediaSource.Url.requestHeaders(): Map<String, String> {
    if (cookies.isEmpty()) return headers
    val cookieHeader = cookies.joinToString("; ") { "${it.name}=${it.value}" }
    val existing = headers.entries.firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
    return headers.filterKeys { existing == null || it != existing.key } +
        ("Cookie" to listOfNotNull(existing?.value, cookieHeader).joinToString("; "))
}

/**
 * The Android emulator's goldfish video decoders claim adaptive playback but corrupt frames when an HLS
 * stream switches resolution (green and magenta blocks, a zoomed picture). Prefer any other decoder for
 * video there, which in practice is the platform's software decoder. Real devices have no goldfish codecs.
 */
@OptIn(UnstableApi::class)
private val CodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
    val decoders = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
    if (!MimeTypes.isVideo(mimeType)) return@MediaCodecSelector decoders
    val (emulatorDecoders, otherDecoders) = decoders.partition { it.name.contains("goldfish", ignoreCase = true) }
    otherDecoders + emulatorDecoders
}

/** Delegates to [delegate] but stops all loading while [isLoadingPaused], keeping what is already buffered. */
@OptIn(UnstableApi::class)
private class ParkableLoadControl(private val delegate: DefaultLoadControl) : LoadControl {
    var isLoadingPaused = false

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        !isLoadingPaused && delegate.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: ExoMediaSource.MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean = !isLoadingPaused && delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean = delegate.shouldStartPlayback(parameters)

    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>,
    ) = delegate.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)

    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
}

@OptIn(UnstableApi::class)
private fun BufferingConfig.toLoadControl(): DefaultLoadControl {
    val toStart = minBufferToStart.inWholeMilliseconds.toInt()
    val afterRebuffer = minBufferAfterRebuffer.inWholeMilliseconds.toInt()
    // Media3 requires both thresholds to be within the minimum buffer.
    val minBuffer = maxOf(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, toStart, afterRebuffer)
    val maxBuffer = maxOf(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, minBuffer)
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBuffer, maxBuffer, toStart, afterRebuffer)
        .build()
}
