package co.liebi.videoplayer.core

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Something that happened to a player. Events are derived in common code from normalized state
 * transitions, so Android and iOS emit the same sequence for the same scenario.
 *
 * Event streams are hot with no replay, delivered on the main thread in order per player.
 */
@Immutable
public data class PlayerEvent(
    val playerId: String,
    /** `null` when no item is loaded. */
    val itemId: String?,
    val position: Duration?,
    /** Wall-clock time. */
    val timestamp: Instant,
    val type: PlayerEventType,
)

public sealed interface PlayerEventType {

    /** The lifecycle moved from Active to Suspended. */
    public data object PlayerSuspended : PlayerEventType

    /** The lifecycle moved from Suspended to Active. */
    public data object PlayerResumed : PlayerEventType

    /** `release()` was called. No events follow. */
    public data object PlayerReleased : PlayerEventType

    /** The current item changed. */
    public data class ItemChanged(val previousItemId: String?) : PlayerEventType

    /** The first frame of the item was rendered. */
    public data class FirstFrameRendered(val timeSinceLoad: Duration) : PlayerEventType

    /** Playback began after `play()` or an automatic resume. Not emitted when a stall ends. */
    public data class PlaybackStarted(val isFirstStart: Boolean) : PlayerEventType

    /** Play intent was cleared. */
    public data class PlaybackPaused(val reason: PauseReason) : PlayerEventType

    /** The end was reached. With auto replay, this fires on every loop. */
    public data object PlaybackCompleted : PlayerEventType

    /** A stall during playback. Not emitted for the initial load or for seeks. */
    public data object BufferingStarted : PlayerEventType

    public data class BufferingEnded(val stallDuration: Duration) : PlayerEventType

    public data class SeekStarted(val from: Duration, val to: Duration) : PlayerEventType

    public data class SeekCompleted(val position: Duration) : PlayerEventType

    /** A new source was applied through the [SourceRefresher] or `updateSource`. */
    public data class CredentialsRefreshed(val trigger: CredentialsRefreshTrigger) : PlayerEventType

    public data class RetryScheduled(val attempt: Int, val delay: Duration, val error: PlayerError) : PlayerEventType

    /** Any error. [isFinal] when the player enters [PlaybackStatus.Error]. */
    public data class PlaybackError(val error: PlayerError, val isFinal: Boolean) : PlayerEventType
}

public enum class CredentialsRefreshTrigger {
    /** The [SourceRefresher] returned a new source after a 401 or 403. */
    Failure,

    /** The app called `updateSource`. */
    Update,
}
