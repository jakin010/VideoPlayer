package co.liebi.videoplayer.core.internal

import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.BufferingConfig
import co.liebi.videoplayer.core.ErrorCategory
import co.liebi.videoplayer.core.InternalVideoPlayerApi
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.VideoPlayerDiagnostics
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAudioTimePitchAlgorithmTimeDomain
import platform.AVFoundation.actionAtItemEnd
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.pause
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.volume
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerActionAtItemEndPause
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVURLAssetHTTPCookiesKey
import platform.AVFoundation.CMTimeRangeValue
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.presentationSize
import platform.AVFoundation.seekToTime
import platform.AVFoundation.seekableTimeRanges
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieSecure
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.darwin.NSObjectProtocol
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * AVFoundation engine. AVPlayer has no equivalent of Media3's start thresholds, so this engine turns off
 * `automaticallyWaitsToMinimizeStalling` and only sets a rate once [BufferGate] is satisfied (§5).
 */
@OptIn(ExperimentalForeignApi::class, InternalVideoPlayerApi::class)
internal class AVPlaybackEngine(private val buffering: BufferingConfig) : PlaybackEngine {

    private val player = AVPlayer().apply {
        automaticallyWaitsToMinimizeStalling = false
        // AirPlay stays off in v1 so state remains predictable (§19).
        allowsExternalPlayback = false
        actionAtItemEnd = AVPlayerActionAtItemEndPause
        VideoPlayerDiagnostics.onNativePlayerCreated?.invoke(this)
    }

    /** Owned by the engine and moved into whichever surface is active, so a handoff never re-prepares. */
    val playerLayer: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(player)

    private var listener: EngineListener? = null
    private var item: AVPlayerItem? = null
    private val itemObservations = mutableListOf<KeyValueObservation>()
    private val notificationTokens = mutableListOf<NSObjectProtocol>()

    private var status = EngineStatus.Idle
    private var playWhenReady = false
    private var speed = 1f
    private var pendingStart: Duration? = null
    private var isSeeking = false
    private var isRebuffering = false
    private var firstFrameReported = false
    private var lastTimeline: EngineTimeline? = null
    private var isReleased = false
    private var isParked = false

    private val layerObservation = KeyValueObservation(playerLayer, "readyForDisplay") { reportFirstFrameIfReady() }

    override fun setListener(listener: EngineListener?) {
        this.listener = listener
    }

    override fun load(source: MediaSource, startPosition: Duration) {
        detachItem()
        val url = source.toNSURL()
        if (url == null) {
            listener?.onError(ErrorClassifier.of(ErrorCategory.NotFound, "Invalid URL"))
            return
        }
        val asset = AVURLAsset.URLAssetWithURL(url, options = source.assetOptions(url))
        val newItem = AVPlayerItem.playerItemWithAsset(asset).apply {
            audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmTimeDomain
        }
        item = newItem
        pendingStart = startPosition.takeIf { it > Duration.ZERO }
        observe(newItem)
        player.replaceCurrentItemWithPlayerItem(newItem)
        updateStatus(EngineStatus.Buffering)
        applyRate()
    }

    override fun unload() {
        detachItem()
        updateStatus(EngineStatus.Idle)
        applyRate()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        this.playWhenReady = playWhenReady
        applyRate()
    }

    override fun seekTo(position: Duration) {
        if (item == null) return
        isRebuffering = false
        isSeeking = true
        updateStatus(EngineStatus.Buffering)
        seek(position) {
            listener?.onSeekCompleted()
            evaluateReadiness()
        }
    }

    override fun setVolume(volume: Float) {
        player.volume = volume
    }

    override fun setPlaybackSpeed(speed: Float) {
        this.speed = speed
        applyRate()
    }

    override fun setParked(parked: Boolean) {
        isParked = parked
        // A tiny forward buffer stops AVPlayer from downloading more while keeping what it has.
        item?.preferredForwardBufferDuration = if (parked) ParkedForwardBufferSeconds else 0.0
        if (parked) {
            playWhenReady = false
        } else {
            // The layer still holds this item's frame, so report it again for the new selection.
            firstFrameReported = false
            reportFirstFrameIfReady()
        }
        applyRate()
    }

    override val currentPosition: Duration
        get() = player.currentTime().toDurationOrNull() ?: Duration.ZERO

    override val bufferedPosition: Duration
        get() = item?.let { bufferedEnd(it, currentPosition) } ?: currentPosition

    override fun release() {
        if (isReleased) return
        isReleased = true
        listener = null
        detachItem()
        layerObservation.invalidate()
        playerLayer.removeFromSuperlayer()
        player.pause()
    }

    private fun observe(item: AVPlayerItem) {
        fun onItem(block: () -> Unit): () -> Unit = { if (this.item === item && !isReleased) block() }

        itemObservations += KeyValueObservation(item, "status", onItem(::onItemStatusChanged))
        itemObservations += KeyValueObservation(item, "loadedTimeRanges", onItem(::evaluateReadiness))
        itemObservations += KeyValueObservation(item, "duration", onItem(::reportTimeline))
        itemObservations += KeyValueObservation(item, "presentationSize", onItem(::reportVideoSize))
        itemObservations += KeyValueObservation(item, "playbackBufferEmpty", onItem { if (item.playbackBufferEmpty) onStall() })

        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        notificationTokens += center.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, queue) { _ ->
            onItem(::onPlayedToEnd)()
        }
        notificationTokens += center.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, queue) { notification ->
            onItem { onFailed(notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError) }()
        }
        notificationTokens += center.addObserverForName(AVPlayerItemPlaybackStalledNotification, item, queue) { _ ->
            onItem(::onStall)()
        }
    }

    private fun detachItem() {
        itemObservations.forEach { it.invalidate() }
        itemObservations.clear()
        notificationTokens.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        notificationTokens.clear()
        if (item != null) player.replaceCurrentItemWithPlayerItem(null)
        item = null
        pendingStart = null
        isSeeking = false
        isRebuffering = false
        firstFrameReported = false
        lastTimeline = null
    }

    private fun onItemStatusChanged() {
        val item = item ?: return
        when (item.status) {
            AVPlayerItemStatusReadyToPlay -> {
                reportTimeline()
                reportVideoSize()
                val start = pendingStart
                if (start != null) {
                    pendingStart = null
                    isSeeking = true
                    seek(start, ::evaluateReadiness)
                } else {
                    evaluateReadiness()
                }
            }
            AVPlayerItemStatusFailed -> onFailed(item.error)
        }
    }

    /** Moves to Ready once enough media is buffered ahead of the position (§5). */
    private fun evaluateReadiness() {
        val item = item ?: return
        if (status == EngineStatus.Ready || status == EngineStatus.Ended) return
        if (item.status != AVPlayerItemStatusReadyToPlay || pendingStart != null || isSeeking) return
        val position = currentPosition
        val bufferedAhead = bufferedEnd(item, position) - position
        val remaining = item.duration.toDurationOrNull()?.let { it - position }
        val threshold = if (isRebuffering) buffering.minBufferAfterRebuffer else buffering.minBufferToStart
        if (!BufferGate.isSatisfied(bufferedAhead, remaining, threshold)) return
        isRebuffering = false
        updateStatus(EngineStatus.Ready)
        applyRate()
        reportFirstFrameIfReady()
    }

    private fun onStall() {
        val item = item ?: return
        if (status != EngineStatus.Ready || !playWhenReady || isSeeking) return
        // Running dry at the very end is not a stall; the end notification follows.
        val remaining = item.duration.toDurationOrNull()?.let { it - currentPosition }
        if (remaining != null && remaining <= EndTolerance) return
        isRebuffering = true
        updateStatus(EngineStatus.Buffering)
        applyRate()
        evaluateReadiness()
    }

    private fun onPlayedToEnd() {
        isSeeking = false
        updateStatus(EngineStatus.Ended)
        applyRate()
    }

    private fun onFailed(error: NSError?) {
        isSeeking = false
        player.rate = 0f
        listener?.onError(error.toPlayerError(item))
    }

    private fun seek(position: Duration, onComplete: () -> Unit) {
        val target = item ?: return
        applyRate()
        val time = CMTimeMakeWithSeconds(position.toDouble(DurationUnit.SECONDS), PreferredTimescale)
        val zero = CMTimeMake(0, 1)
        player.seekToTime(time, toleranceBefore = zero, toleranceAfter = zero) { finished ->
            onMainThread {
                // An unfinished seek was superseded by a newer one, which reports its own completion.
                if (!finished || isReleased || item !== target) return@onMainThread
                isSeeking = false
                onComplete()
            }
        }
    }

    private fun applyRate() {
        val rate = if (status == EngineStatus.Ready && playWhenReady && !isSeeking && !isParked) speed else 0f
        if (player.rate != rate) player.rate = rate
    }

    private fun updateStatus(newStatus: EngineStatus) {
        if (status == newStatus) return
        status = newStatus
        listener?.onStatusChanged(newStatus)
    }

    private fun reportTimeline() {
        val item = item ?: return
        if (item.status != AVPlayerItemStatusReadyToPlay) return
        val duration = item.duration.toDurationOrNull()
        val isLive = duration == null
        val timeline = EngineTimeline(
            duration = duration,
            isLive = isLive,
            isSeekable = !isLive && item.seekableTimeRanges.isNotEmpty(),
        )
        if (timeline == lastTimeline) return
        lastTimeline = timeline
        listener?.onTimelineChanged(timeline)
    }

    private fun reportVideoSize() {
        val size = item?.presentationSize?.useContents { IntSize(width.roundToInt(), height.roundToInt()) } ?: return
        if (size.width > 0 && size.height > 0) listener?.onVideoSizeChanged(size)
    }

    private fun reportFirstFrameIfReady() {
        if (firstFrameReported || item == null || !playerLayer.readyForDisplay) return
        firstFrameReported = true
        listener?.onFirstFrameRendered()
    }

    private companion object {
        const val PreferredTimescale = 1000
        const val ParkedForwardBufferSeconds = 1.0
        val EndTolerance = 500.milliseconds
        val RangeTolerance = 100.milliseconds
    }

    /** End of the loaded range containing [position], or [position] when nothing around it is loaded. */
    private fun bufferedEnd(item: AVPlayerItem, position: Duration): Duration {
        for (value in item.loadedTimeRanges) {
            val range = (value as? NSValue)?.CMTimeRangeValue ?: continue
            val start = range.useContents { start.readValue() }.toDurationOrNull() ?: continue
            val end = CMTimeRangeGetEnd(range).toDurationOrNull() ?: continue
            if (position >= start - RangeTolerance && position <= end + RangeTolerance) return end
        }
        return position
    }
}

/** `null` for invalid or indefinite times, such as the duration of a live stream. */
@OptIn(ExperimentalForeignApi::class)
private fun CValue<CMTime>.toDurationOrNull(): Duration? {
    val seconds = CMTimeGetSeconds(this)
    return if (seconds.isFinite() && seconds >= 0) seconds.seconds else null
}

private fun MediaSource.toNSURL(): NSURL? = when (this) {
    is MediaSource.Url -> NSURL.URLWithString(url)
    is MediaSource.File -> NSURL.fileURLWithPath(path)
    is MediaSource.Resource -> NSURL.URLWithString(uri)
}

/**
 * Cookies use the public AVURLAssetHTTPCookiesKey. Custom headers have no public API; this uses the
 * undocumented header option, which is the open decision §19.1 and needs validating on devices.
 */
private fun MediaSource.assetOptions(url: NSURL): Map<Any?, Any?>? {
    if (this !is MediaSource.Url) return null
    val options = mutableMapOf<Any?, Any?>()
    if (headers.isNotEmpty()) options[HeaderFieldsKey] = headers
    if (cookies.isNotEmpty()) options[AVURLAssetHTTPCookiesKey] = cookies.mapNotNull { it.toNSHTTPCookie(url) }
    return options.ifEmpty { null }
}

private fun co.liebi.videoplayer.core.Cookie.toNSHTTPCookie(url: NSURL): NSHTTPCookie? {
    val properties = mutableMapOf<Any?, Any?>(
        NSHTTPCookieName to name,
        NSHTTPCookieValue to value,
        NSHTTPCookieDomain to (domain ?: url.host ?: return null),
        NSHTTPCookiePath to path,
    )
    if (isSecure) properties[NSHTTPCookieSecure] = "TRUE"
    return NSHTTPCookie.cookieWithProperties(properties)
}

private const val HeaderFieldsKey = "AVURLAssetHTTPHeaderFieldsKey"
