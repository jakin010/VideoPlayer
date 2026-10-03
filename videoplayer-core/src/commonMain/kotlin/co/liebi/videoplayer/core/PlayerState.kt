package co.liebi.videoplayer.core

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntSize
import kotlin.time.Duration

/**
 * Everything about a player except its position, which lives in [PlaybackProgress]
 * so that only position-dependent UI recomposes on every tick.
 */
@Immutable
public data class PlayerState(
    /** What the engine can do right now. */
    val status: PlaybackStatus = PlaybackStatus.Idle,
    /** Play intent: whether the user (or app) wants playback. */
    val playWhenReady: Boolean = false,
    /** Why play intent was last cleared; `null` while play intent is set. */
    val pauseReason: PauseReason? = null,
    val lifecycle: PlayerLifecycle = PlayerLifecycle.Active,
    val currentItemId: String? = null,
    /** `null` while unknown and for live streams. */
    val duration: Duration? = null,
    /** The range [seekTo] accepts. `0..duration` for VOD. */
    val seekableRange: ClosedRange<Duration>? = null,
    val isSeekable: Boolean = false,
    /** Live streams are detected but not supported in v1: they are not seekable and have no duration. */
    val isLive: Boolean = false,
    val isSeeking: Boolean = false,
    /** Resets on item change and after suspension. Surfaces show their poster until this is `true`. */
    val isFirstFrameRendered: Boolean = false,
    /** The native size of the video, once known. */
    val videoSize: IntSize? = null,
    /** Player gain from 0 to 1, independent of system volume. */
    val volume: Float = 1f,
    val isMuted: Boolean = false,
    val playbackSpeed: Float = 1f,
    /** When the end is reached, seek to the start and keep playing. */
    val autoReplay: Boolean = true,
    /** The most recent error. */
    val error: PlayerError? = null,
    /** The automatic retry in progress, or 0 when not retrying. */
    val retryAttempt: Int = 0,
    val presentation: Presentation = Presentation.Inline,
    val isAudioInterrupted: Boolean = false,
) {
    /** Playback is actually running: [PlaybackStatus.Ready] with play intent set and no audio interruption. */
    val isPlaying: Boolean
        get() = status == PlaybackStatus.Ready && playWhenReady && !isAudioInterrupted
}

/** What the engine can do, independent of play intent. */
public enum class PlaybackStatus {
    /** No item loaded, or the player is suspended. */
    Idle,

    /** Loading the item until it is first ready, including the minimum buffer and automatic retries. */
    Preparing,

    /** Was ready, now waiting for data after a stall or a seek. */
    Buffering,

    /** Can play immediately. */
    Ready,

    /** Reached the end of the item. */
    Ended,

    /** Failed permanently, or automatic retries are exhausted. */
    Error,
}

/** Why play intent was cleared. Only some reasons allow playback to resume automatically. */
public enum class PauseReason {
    /** `pause()` was called. Never resumes automatically, and is never replaced by an automatic reason. */
    User,

    /** A long press on the video. Resumes on release if the reason is still [Hold]. */
    Hold,

    /** A call, audio focus loss or headphones being unplugged. Resumes only when the system signals it. */
    Interruption,

    /** The app moved to the background. Resumes only with `resumeAfterBackground`. */
    Background,

    /** Another player became the active one. Never resumes automatically. */
    Coordinator,

    /** The player was suspended. Never resumes automatically. */
    Suspended,

    /** The item reached its end. Never resumes automatically. */
    Ended,
}

public enum class PlayerLifecycle {
    /** The native player is allocated and the current item is prepared. */
    Active,

    /** The native player is released; the item list, positions and settings are kept. */
    Suspended,

    /** Permanently released. Later commands are ignored. */
    Released,
}

public enum class Presentation {
    Inline,
    Fullscreen,
}

/** High-frequency position data. Updated about every 250 ms while playing. */
@Immutable
public data class PlaybackProgress(
    val position: Duration = Duration.ZERO,
    /** End of the contiguous buffered range that contains [position]. */
    val bufferedPosition: Duration = Duration.ZERO,
)
