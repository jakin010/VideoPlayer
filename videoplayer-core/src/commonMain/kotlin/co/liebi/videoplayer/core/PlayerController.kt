package co.liebi.videoplayer.core

import co.liebi.videoplayer.core.internal.DefaultPlayerController
import co.liebi.videoplayer.core.internal.createPlatformEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * Controls one player. A controller can outlive any composable, so it can live in a ViewModel.
 *
 * All methods must be called on the main thread. State and events are delivered on the main thread.
 * This is an interface so apps can fake it in tests and previews.
 */
public interface PlayerController {

    /** Identifies this player in [PlayerEvent.playerId]. */
    public val id: String

    /** Everything except position. */
    public val state: StateFlow<PlayerState>

    /** Position and buffered position, updated about every 250 ms while playing. */
    public val progress: StateFlow<PlaybackProgress>

    /** This player's events. Hot, with no replay. */
    public val events: Flow<PlayerEvent>

    /** Sets play intent. Playback starts once the status reaches [PlaybackStatus.Ready]. */
    public fun play()

    /** Clears play intent with [PauseReason.User], in any status. */
    public fun pause()

    /**
     * Pauses for a press-and-hold gesture, with [PauseReason.Hold]. Does nothing unless play intent is set,
     * so holding a paused, ended or failed player has no effect.
     */
    public fun beginHold()

    /**
     * Ends a hold. Playback resumes only if the player is still paused for the hold: a user pause,
     * an interruption or backgrounding in the meantime wins.
     */
    public fun endHold()

    /**
     * Seeks to [position], clamped to the seekable range. Ignored when the media is not seekable.
     * Rapid calls coalesce: after the in-flight seek, only the latest target runs.
     */
    public fun seekTo(position: Duration)

    /** Player gain from 0 to 1, not the system volume. */
    public fun setVolume(volume: Float)

    public fun setMuted(muted: Boolean)

    /** 0.5x to 2.0x with pitch preserved. Kept across items. */
    public fun setPlaybackSpeed(speed: Float)

    /**
     * Loop the item when it reaches its end instead of stopping in [PlaybackStatus.Ended]. Kept across items.
     * Turning it on while the item has already ended does not restart it.
     */
    public fun setAutoReplay(enabled: Boolean)

    /**
     * Replaces the item list. Positions of items that remain are kept. If the current item is removed,
     * the player stops and goes to [PlaybackStatus.Idle].
     *
     * @throws IllegalArgumentException if two items share an ID.
     */
    public fun setItems(items: List<MediaItem>)

    /** Loads the item with [id], resuming at its remembered position. Selecting the current item does nothing. */
    public fun selectItem(id: String)

    /**
     * Swaps an item's source, for example to refresh credentials before they expire.
     * The current item re-prepares at its current position; other items use it at their next load.
     */
    public fun updateSource(itemId: String, source: MediaSource)

    /** Re-prepares after an error and resets the retry counter. */
    public fun retry()

    /** Releases the native player now but keeps items, positions and settings. `play()` resumes. */
    public fun suspend()

    /** Permanently releases the player. Idempotent; later commands are ignored. */
    public fun release()
}

/**
 * Called once when an item fails with HTTP 401 or 403. Return a new source to retry at the same position,
 * or `null` to fail with [ErrorCategory.AccessDenied].
 */
public fun interface SourceRefresher {
    public suspend fun refresh(itemId: String, error: PlayerError): MediaSource.Url?
}

/**
 * Creates an app-owned controller. The app calls [PlayerController.release] when done,
 * for example in `ViewModel.onCleared`. For players in lazy lists, prefer [rememberPlayerController].
 *
 * @param coordinator The coordinator this player belongs to (§14).
 */
public fun PlayerController(
    configuration: PlayerConfiguration = PlayerConfiguration(),
    sourceRefresher: SourceRefresher? = null,
    coordinator: PlayerCoordinator = PlayerCoordinator.Default,
): PlayerController = DefaultPlayerController(
    configuration = configuration,
    sourceRefresher = sourceRefresher,
    engineFactory = { createPlatformEngine(configuration.buffering) },
    coordinator = coordinator,
)
