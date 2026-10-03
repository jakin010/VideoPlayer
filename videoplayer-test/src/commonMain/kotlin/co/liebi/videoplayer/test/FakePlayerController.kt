package co.liebi.videoplayer.test

import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerEventType
import co.liebi.videoplayer.core.PlayerLifecycle
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.Presentation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * A [PlayerController] without a native player, for UI tests and `@Preview`.
 *
 * Commands update [state] the obvious way and are recorded in [calls]. Tests can set any state
 * directly with [setState], [setProgress] and [emit].
 */
public class FakePlayerController(
    initialState: PlayerState = PlayerState(),
    initialProgress: PlaybackProgress = PlaybackProgress(),
    override val id: String = "fake-player",
) : PlayerController {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(initialProgress)
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    private val _calls = mutableListOf<Call>()

    /** Every command received, in order. */
    public val calls: List<Call> get() = _calls.toList()

    /** The list passed to the latest [setItems]. */
    public var items: List<MediaItem> = emptyList()
        private set

    public fun setState(state: PlayerState) {
        _state.value = state
    }

    public fun updateState(transform: (PlayerState) -> PlayerState) {
        _state.update(transform)
    }

    public fun setProgress(progress: PlaybackProgress) {
        _progress.value = progress
    }

    /** Emits an event as this player, stamped with the current item and position. */
    public fun emit(type: PlayerEventType) {
        _events.tryEmit(
            PlayerEvent(
                playerId = id,
                itemId = state.value.currentItemId,
                position = progress.value.position,
                timestamp = Clock.System.now(),
                type = type,
            ),
        )
    }

    override fun play() {
        record(Call.Play)
        _state.update { it.copy(playWhenReady = true, pauseReason = null) }
    }

    override fun pause() {
        record(Call.Pause)
        _state.update { it.copy(playWhenReady = false, pauseReason = PauseReason.User) }
    }

    override fun beginHold() {
        record(Call.BeginHold)
        _state.update {
            if (it.playWhenReady && it.status != PlaybackStatus.Ended && it.status != PlaybackStatus.Error) {
                it.copy(playWhenReady = false, pauseReason = PauseReason.Hold)
            } else {
                it
            }
        }
    }

    override fun endHold() {
        record(Call.EndHold)
        _state.update { if (!it.playWhenReady && it.pauseReason == PauseReason.Hold) it.copy(playWhenReady = true, pauseReason = null) else it }
    }

    override fun seekTo(position: Duration) {
        record(Call.SeekTo(position))
        _progress.update { it.copy(position = position) }
    }

    override fun setVolume(volume: Float) {
        record(Call.SetVolume(volume))
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    override fun setMuted(muted: Boolean) {
        record(Call.SetMuted(muted))
        _state.update { it.copy(isMuted = muted) }
    }

    override fun setPlaybackSpeed(speed: Float) {
        record(Call.SetPlaybackSpeed(speed))
        _state.update { it.copy(playbackSpeed = speed) }
    }

    override fun setAutoReplay(enabled: Boolean) {
        record(Call.SetAutoReplay(enabled))
        _state.update { it.copy(autoReplay = enabled) }
    }

    override fun setItems(items: List<MediaItem>) {
        record(Call.SetItems(items))
        this.items = items
    }

    override fun selectItem(id: String) {
        record(Call.SelectItem(id))
        _state.update { it.copy(currentItemId = id) }
    }

    override fun updateSource(itemId: String, source: MediaSource) {
        record(Call.UpdateSource(itemId, source))
        items = items.map { if (it.id == itemId) it.copy(source = source) else it }
    }

    override fun retry() {
        record(Call.Retry)
        _state.update { if (it.status == PlaybackStatus.Error) it.copy(status = PlaybackStatus.Preparing, retryAttempt = 0) else it }
    }

    override fun suspend() {
        record(Call.Suspend)
        _state.update { it.copy(lifecycle = PlayerLifecycle.Suspended, status = PlaybackStatus.Idle, playWhenReady = false) }
    }

    /** Goes fullscreen only when [PlayerState.isFullscreenAvailable], like a real player. */
    override fun enterFullscreen() {
        record(Call.EnterFullscreen)
        _state.update { if (it.isFullscreenAvailable) it.copy(presentation = Presentation.Fullscreen) else it }
    }

    override fun exitFullscreen() {
        record(Call.ExitFullscreen)
        _state.update { it.copy(presentation = Presentation.Inline) }
    }

    override fun release() {
        record(Call.Release)
        _state.update { it.copy(lifecycle = PlayerLifecycle.Released, status = PlaybackStatus.Idle, playWhenReady = false) }
    }

    private fun record(call: Call) {
        _calls += call
    }

    public sealed interface Call {
        public data object Play : Call
        public data object Pause : Call
        public data object BeginHold : Call
        public data object EndHold : Call
        public data class SeekTo(val position: Duration) : Call
        public data class SetVolume(val volume: Float) : Call
        public data class SetMuted(val muted: Boolean) : Call
        public data class SetPlaybackSpeed(val speed: Float) : Call
        public data class SetAutoReplay(val enabled: Boolean) : Call
        public data class SetItems(val items: List<MediaItem>) : Call
        public data class SelectItem(val id: String) : Call
        public data class UpdateSource(val itemId: String, val source: MediaSource) : Call
        public data object Retry : Call
        public data object Suspend : Call
        public data object EnterFullscreen : Call
        public data object ExitFullscreen : Call
        public data object Release : Call
    }
}
