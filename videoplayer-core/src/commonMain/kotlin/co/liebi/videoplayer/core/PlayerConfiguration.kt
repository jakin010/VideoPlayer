package co.liebi.videoplayer.core

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every default the player uses. Configuration is fixed when a controller is created;
 * runtime changes go through controller methods.
 */
@Immutable
public data class PlayerConfiguration(
    val playback: PlaybackConfig = PlaybackConfig(),
    val buffering: BufferingConfig = BufferingConfig(),
    val retry: RetryConfig = RetryConfig(),
    val lifecycle: LifecycleConfig = LifecycleConfig(),
)

@Immutable
public data class PlaybackConfig(
    /**
     * Initial auto replay: when the end is reached, seek to the start and keep playing; PlaybackCompleted
     * fires on every loop. On by default. When off, the player stops in [PlaybackStatus.Ended].
     * Change it at runtime with `PlayerController.setAutoReplay`.
     */
    val autoReplay: Boolean = true,
    /** `selectItem` sets play intent. Otherwise the new item starts paused. */
    val playOnItemSelected: Boolean = true,
    val initialMuted: Boolean = false,
    val initialVolume: Float = 1f,
    /** 0.5 to 2.0. */
    val initialPlaybackSpeed: Float = 1f,
) {
    init {
        require(initialVolume in 0f..1f) { "initialVolume must be in 0..1" }
        require(initialPlaybackSpeed in MIN_SPEED..MAX_SPEED) { "initialPlaybackSpeed must be in $MIN_SPEED..$MAX_SPEED" }
    }

    internal companion object {
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
    }
}

/**
 * Minimum media buffered ahead of the position before playback starts.
 * When less media remains than a threshold, the remaining duration is used instead.
 */
@Immutable
public data class BufferingConfig(
    /** First start, after a seek and after selecting an item. */
    val minBufferToStart: Duration = 2.5.seconds,
    /** Resuming after a stall during playback. */
    val minBufferAfterRebuffer: Duration = 5.seconds,
) {
    init {
        require(!minBufferToStart.isNegative() && !minBufferAfterRebuffer.isNegative()) { "Buffer thresholds must not be negative" }
    }
}

@Immutable
public data class RetryConfig(
    val enabled: Boolean = true,
    val maxAttempts: Int = 3,
    /** Fixed delay before each automatic retry. */
    val delay: Duration = 2.seconds,
) {
    init {
        require(maxAttempts >= 0) { "maxAttempts must not be negative" }
    }
}

@Immutable
public data class LifecycleConfig(
    /**
     * Moving to the background pauses every player with [PauseReason.Background]. With this on, a player
     * resumes on return if that is still its pause reason; a user pause in between wins (§13).
     */
    val resumeAfterBackground: Boolean = false,
    val keepScreenAwakeWhilePlaying: Boolean = true,
    /** How long a suspended controller keeps item positions. */
    val positionRetention: Duration = 30.seconds,
    /**
     * How many previously played items stay prepared in memory, paused, so switching back to them is
     * instant. Each one keeps a native player and its buffer, and stops downloading while kept. 0 turns it off.
     */
    val keepPreparedItems: Int = 1,
) {
    init {
        require(keepPreparedItems >= 0) { "keepPreparedItems must not be negative" }
    }
}
