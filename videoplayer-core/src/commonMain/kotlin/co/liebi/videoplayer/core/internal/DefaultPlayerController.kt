package co.liebi.videoplayer.core.internal

import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.CredentialsRefreshTrigger
import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.LifecycleConfig
import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackConfig
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerError
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerEventType
import co.liebi.videoplayer.core.PlayerLifecycle
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.Presentation
import co.liebi.videoplayer.core.SourceRefresher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The player's behavior, shared by every platform. A [PlaybackEngine] reports raw facts and this class
 * derives status, play intent, events, retries and position memory from them (§2, §4–§9, §15).
 *
 * Not thread-safe: everything runs on the main thread. Commands and engine callbacks go through [operation],
 * which serializes re-entrant calls (engines and event collectors may call back synchronously).
 */
internal class DefaultPlayerController(
    private val configuration: PlayerConfiguration,
    private val sourceRefresher: SourceRefresher?,
    private val engineFactory: () -> PlaybackEngine,
    private val coordinator: PlayerCoordinator,
    /** Set for coordinator-owned controllers, which the coordinator releases after retention (§8). */
    private val ownerKey: String? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: Clock = Clock.System,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val log: (String) -> Unit = ::logWarning,
    override val id: String = nextPlayerId(),
) : PlayerController {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(
        PlayerState(
            volume = configuration.playback.initialVolume,
            isMuted = configuration.playback.initialMuted,
            playbackSpeed = configuration.playback.initialPlaybackSpeed,
            autoReplay = configuration.playback.autoReplay,
            isFullscreenAvailable = coordinator.isFullscreenAvailable,
        ),
    )
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    override val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = EventBufferSize,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    private val _engine = MutableStateFlow<PlaybackEngine?>(null)

    /** The native engine while the player is active. Surfaces render it. */
    val engine: StateFlow<PlaybackEngine?> = _engine.asStateFlow()

    private val surfaces = mutableListOf<Any>()
    private val _activeSurface = MutableStateFlow<Any?>(null)

    /** The most recently attached surface. It is the only one that renders the video (§10). */
    val activeSurface: StateFlow<Any?> = _activeSurface.asStateFlow()

    private var items: List<MediaItem> = emptyList()
    private val positionMemory = mutableMapOf<String, Duration>()

    /** Recently played items kept prepared and paused by item ID, oldest first, so switching back is instant. */
    private val preparedItems = LinkedHashMap<String, PreparedItem>()

    // Per load. Reset by resetLoadState().
    private var hasBeenReady = false
    private var preReadySeek: Duration? = null
    private var seekInFlight = false
    private var queuedSeek: Duration? = null
    private var stallMark: TimeMark? = null

    // Per item.
    private var loadMark: TimeMark? = null
    private var hasStartedItem = false
    private var credentialsRefreshed = false

    /** Last known position of the current item, or the target of the latest seek. */
    private var lastPosition = Duration.ZERO

    /** Where the latest reload after an error started. Playing past it resets the retry counter (§9). */
    private var recoveryPosition: Duration? = null

    /** PlaybackStarted was already emitted for the current play intent. */
    private var startedForIntent = false

    /** Play intent was set (or held) when an interruption cleared it, so the end of the interruption may resume. */
    private var resumeAfterInterruption = false

    /** Play intent was set (or held) when the app moved to the background. */
    private var resumeAfterBackground = false

    private var recoveryJob: Job? = null
    private var progressJob: Job? = null
    private var suspendJob: Job? = null
    private var retentionJob: Job? = null

    private var inOperation = false
    private val deferred = ArrayDeque<() -> Unit>()

    val isReleased: Boolean
        get() = _state.value.lifecycle == PlayerLifecycle.Released

    /** Native players held: the current engine plus items kept prepared. Counted against the coordinator's cap. */
    val nativePlayerCount: Int
        get() = (if (_engine.value != null) 1 else 0) + preparedItems.size

    /**
     * Meant to play with sound, which needs the playback audio session. Follows intent rather than
     * [PlayerState.isPlaying], so stalls don't flip the session back and forth.
     */
    val isAudible: Boolean
        get() = _state.value.let {
            it.playWhenReady && it.lifecycle == PlayerLifecycle.Active && it.status != PlaybackStatus.Error &&
                !it.isMuted && it.volume > 0f
        }

    val keepsScreenAwake: Boolean
        get() = configuration.lifecycle.keepScreenAwakeWhilePlaying

    private val currentItem: MediaItem?
        get() = _state.value.currentItemId?.let { id -> items.firstOrNull { it.id == id } }

    // region Commands

    override fun play() = command("play") {
        val wasStatus = _state.value.status
        if (_state.value.isAudioInterrupted) {
            // The user takes over from an interruption whose end the system may never report.
            resumeAfterInterruption = false
            _state.update { it.copy(isAudioInterrupted = false) }
        }
        setPlayIntent()
        when {
            _state.value.lifecycle == PlayerLifecycle.Suspended -> resume()
            wasStatus == PlaybackStatus.Error -> reload(lastPosition, resetRetries = true)
            wasStatus == PlaybackStatus.Ended -> restartFromBeginning()
        }
    }

    override fun pause() = command("pause") {
        clearPlayIntent(PauseReason.User)
    }

    override fun beginHold() = command("beginHold") {
        val s = _state.value
        val canHold = s.playWhenReady && s.lifecycle == PlayerLifecycle.Active &&
            s.status != PlaybackStatus.Ended && s.status != PlaybackStatus.Error
        if (canHold) clearPlayIntent(PauseReason.Hold)
    }

    override fun endHold() = command("endHold") {
        val s = _state.value
        if (!s.playWhenReady && s.pauseReason == PauseReason.Hold) setPlayIntent()
    }

    override fun seekTo(position: Duration) = command("seekTo") {
        val s = _state.value
        val itemId = s.currentItemId ?: return@command
        val target = position.coerceAtLeast(Duration.ZERO)
        when {
            s.lifecycle == PlayerLifecycle.Suspended || s.status == PlaybackStatus.Error -> {
                // Applied when the item is prepared again.
                val clamped = s.duration?.let { target.coerceAtMost(it) } ?: target
                lastPosition = clamped
                if (s.lifecycle == PlayerLifecycle.Suspended) positionMemory[itemId] = clamped
            }
            // Stored and applied once the item is ready (§6 rule 2).
            !hasBeenReady -> preReadySeek = target
            !s.isSeekable -> Unit
            else -> {
                val clamped = s.seekableRange?.let { target.coerceIn(it) } ?: target
                if (seekInFlight) {
                    queuedSeek = clamped
                    lastPosition = clamped
                } else {
                    startSeek(clamped)
                }
            }
        }
    }

    override fun setVolume(volume: Float) = command("setVolume") {
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
        applyVolume()
    }

    override fun setMuted(muted: Boolean) = command("setMuted") {
        _state.update { it.copy(isMuted = muted) }
        applyVolume()
    }

    override fun setPlaybackSpeed(speed: Float) = command("setPlaybackSpeed") {
        val clamped = speed.coerceIn(PlaybackConfig.MIN_SPEED, PlaybackConfig.MAX_SPEED)
        _state.update { it.copy(playbackSpeed = clamped) }
        _engine.value?.setPlaybackSpeed(clamped)
    }

    override fun setAutoReplay(enabled: Boolean) = command("setAutoReplay") {
        _state.update { it.copy(autoReplay = enabled) }
    }

    override fun setItems(items: List<MediaItem>) {
        val duplicates = items.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate MediaItem ids: $duplicates" }
        command("setItems") {
            val previous = this.items
            this.items = items.toList()
            positionMemory.keys.retainAll(items.mapTo(HashSet()) { it.id })
            releasePreparedItems { id, prepared -> items.none { it.id == id && it.source == prepared.source } }

            val currentId = _state.value.currentItemId ?: return@command
            val current = currentItem
            if (current == null) {
                unloadCurrentItem()
                return@command
            }
            val sourceChanged = previous.firstOrNull { it.id == currentId }?.source != current.source
            val s = _state.value
            if (s.lifecycle == PlayerLifecycle.Active && (sourceChanged || s.status == PlaybackStatus.Error)) {
                reload(currentPosition(), resetRetries = true)
            }
        }
    }

    override fun selectItem(id: String) = command("selectItem") {
        val item = items.firstOrNull { it.id == id }
        if (item == null) {
            log("selectItem ignored: no item with id '$id'")
            return@command
        }
        val previousId = _state.value.currentItemId
        if (previousId == id) return@command

        rememberPosition()
        cancelRecovery()
        val prepared = takePreparedItem(item)
        keepCurrentItemPrepared(previousId)
        resetLoadState()
        resetItemState()
        val start = startPositionFor(item)
        lastPosition = start
        // Before the state names the new item, so whatever reacts to it reads the new item's position.
        publishProgress()
        _state.update { it.withoutItemData().copy(currentItemId = id) }
        emit(PlayerEventType.ItemChanged(previousId))

        if (configuration.playback.playOnItemSelected) setPlayIntent() else clearPlayIntent(PauseReason.User)
        when (_state.value.lifecycle) {
            PlayerLifecycle.Active -> if (prepared != null) activatePrepared(prepared) else load(start)
            PlayerLifecycle.Suspended -> if (_state.value.playWhenReady) resume()
            PlayerLifecycle.Released -> Unit
        }
    }

    override fun updateSource(itemId: String, source: MediaSource) = command("updateSource") {
        if (!replaceSource(itemId, source)) {
            log("updateSource ignored: no item with id '$itemId'")
            return@command
        }
        releasePreparedItems { id, _ -> id == itemId }
        val s = _state.value
        if (itemId == s.currentItemId && s.lifecycle == PlayerLifecycle.Active) {
            emit(PlayerEventType.CredentialsRefreshed(CredentialsRefreshTrigger.Update))
            reload(currentPosition(), resetRetries = false)
        }
    }

    override fun retry() = command("retry") {
        val s = _state.value
        if (s.lifecycle != PlayerLifecycle.Active || s.currentItemId == null) return@command
        if (s.status != PlaybackStatus.Error && recoveryJob?.isActive != true) return@command
        reload(lastPosition, resetRetries = true)
    }

    override fun suspend() = command("suspend") {
        suspendNow()
    }

    override fun enterFullscreen() = command("enterFullscreen") {
        if (_state.value.presentation == Presentation.Fullscreen) return@command
        if (!coordinator.showFullscreen(this)) {
            log("enterFullscreen ignored: no FullscreenHost is placed for this player's coordinator")
            return@command
        }
        setPresentation(Presentation.Fullscreen)
    }

    override fun exitFullscreen() = command("exitFullscreen") {
        if (_state.value.presentation != Presentation.Fullscreen) return@command
        coordinator.hideFullscreen(this)
        setPresentation(Presentation.Inline)
    }

    override fun release() {
        if (isReleased) return
        operation {
            if (isReleased) return@operation
            cancelRecovery()
            progressJob?.cancel()
            suspendJob?.cancel()
            retentionJob?.cancel()
            emit(PlayerEventType.PlayerReleased)
            releaseEngine()
            releasePreparedItems()
            items = emptyList()
            positionMemory.clear()
            surfaces.clear()
            _activeSurface.value = null
            _state.update {
                it.withoutItemData().copy(
                    lifecycle = PlayerLifecycle.Released,
                    status = PlaybackStatus.Idle,
                    playWhenReady = false,
                    currentItemId = null,
                )
            }
            scope.cancel()
            coordinator.unregister(this)
        }
    }

    // endregion

    // region Surfaces

    fun attachSurface(token: Any) = operation {
        if (isReleased) return@operation
        surfaces.remove(token)
        surfaces.add(token)
        _activeSurface.value = token
        coordinator.touch(this)
        suspendJob?.cancel()
        suspendJob = null
        if (_state.value.lifecycle == PlayerLifecycle.Suspended) resume()
    }

    fun detachSurface(token: Any) = operation {
        if (!surfaces.remove(token)) return@operation
        _activeSurface.value = surfaces.lastOrNull()
        if (surfaces.isEmpty() && _state.value.lifecycle == PlayerLifecycle.Active) scheduleSuspend()
    }

    /** Debounced so scrolling back and forth does not thrash the decoder (§8). */
    private fun scheduleSuspend() {
        suspendJob?.cancel()
        suspendJob = scope.launch {
            delay(SuspendDebounce)
            operation { suspendNow() }
        }
    }

    // endregion

    // region Engine callbacks

    private inner class Callbacks(private val owner: PlaybackEngine) : EngineListener {
        private fun dispatch(block: () -> Unit) = operation {
            if (_engine.value === owner && !isReleased) block()
        }

        override fun onStatusChanged(status: EngineStatus) = dispatch { onEngineStatus(status) }
        override fun onTimelineChanged(timeline: EngineTimeline) = dispatch { onEngineTimeline(timeline) }
        override fun onSeekCompleted() = dispatch { onEngineSeekCompleted() }
        override fun onFirstFrameRendered() = dispatch { onEngineFirstFrame() }
        override fun onVideoSizeChanged(size: IntSize) = dispatch { _state.update { it.copy(videoSize = size) } }
        override fun onError(error: PlayerError) = dispatch { onEngineError(error) }
    }

    private fun onEngineStatus(status: EngineStatus) {
        val s = _state.value
        if (s.status == PlaybackStatus.Error || currentItem == null) return
        when (status) {
            EngineStatus.Idle -> Unit
            EngineStatus.Buffering -> {
                if (!hasBeenReady) {
                    _state.update { it.copy(status = PlaybackStatus.Preparing) }
                    return
                }
                if (s.status == PlaybackStatus.Ready && s.playWhenReady && !s.isSeeking && stallMark == null) {
                    stallMark = timeSource.markNow()
                    _state.update { it.copy(status = PlaybackStatus.Buffering) }
                    emit(PlayerEventType.BufferingStarted)
                } else {
                    _state.update { it.copy(status = PlaybackStatus.Buffering) }
                }
            }
            EngineStatus.Ready -> {
                if (!hasBeenReady) {
                    val pending = preReadySeek
                    preReadySeek = null
                    if (pending != null && s.isSeekable) {
                        // Stay in Preparing until the stored seek lands.
                        startSeek(s.seekableRange?.let { pending.coerceIn(it) } ?: pending)
                        return
                    }
                    hasBeenReady = true
                }
                _state.update { it.copy(status = PlaybackStatus.Ready, error = null) }
                stallMark?.let {
                    stallMark = null
                    emit(PlayerEventType.BufferingEnded(it.elapsedNow()))
                }
            }
            EngineStatus.Ended -> onEnded()
        }
    }

    private fun onEnded() {
        val s = _state.value
        if (s.status == PlaybackStatus.Ended) return
        stallMark = null
        s.currentItemId?.let { positionMemory.remove(it) }
        if (s.autoReplay && s.playWhenReady) {
            emit(PlayerEventType.PlaybackCompleted)
            restartFromBeginning()
        } else {
            _state.update { it.copy(status = PlaybackStatus.Ended) }
            emit(PlayerEventType.PlaybackCompleted)
            clearPlayIntent(PauseReason.Ended)
        }
    }

    private fun onEngineTimeline(timeline: EngineTimeline) {
        val duration = if (timeline.isLive) null else timeline.duration
        val range = duration?.takeIf { it > Duration.ZERO }?.let { Duration.ZERO..it }
        _state.update {
            it.copy(
                duration = duration,
                isLive = timeline.isLive,
                seekableRange = range,
                isSeekable = timeline.isSeekable && range != null,
            )
        }
    }

    private fun onEngineSeekCompleted() {
        if (!seekInFlight) return
        seekInFlight = false
        lastPosition = _engine.value?.currentPosition ?: lastPosition
        emit(PlayerEventType.SeekCompleted(lastPosition))
        val next = queuedSeek
        if (next != null) {
            queuedSeek = null
            startSeek(next)
        } else {
            _state.update { it.copy(isSeeking = false) }
        }
    }

    private fun onEngineFirstFrame() {
        if (_state.value.isFirstFrameRendered) return
        _state.update { it.copy(isFirstFrameRendered = true) }
        emit(PlayerEventType.FirstFrameRendered(loadMark?.elapsedNow() ?: Duration.ZERO))
    }

    private fun onEngineError(error: PlayerError) {
        val s = _state.value
        if (s.status == PlaybackStatus.Error) return
        // Resume where the user expects: a pending seek target wins over the engine position.
        lastPosition = preReadySeek ?: queuedSeek ?: currentPosition()
        resetLoadState()
        _state.update { it.copy(isSeeking = false, error = error) }

        val item = currentItem ?: return
        if (sourceRefresher != null && item.source is MediaSource.Url &&
            ErrorClassifier.isCredentialFailure(error) && !credentialsRefreshed
        ) {
            refreshCredentials(item.id, error, sourceRefresher)
            return
        }

        val retry = configuration.retry
        if (error.isRecoverable && retry.enabled && s.retryAttempt < retry.maxAttempts) {
            val attempt = s.retryAttempt + 1
            _state.update { it.copy(status = PlaybackStatus.Preparing, retryAttempt = attempt) }
            emit(PlayerEventType.PlaybackError(error, isFinal = false))
            emit(PlayerEventType.RetryScheduled(attempt, retry.delay, error))
            recoveryJob = scope.launch {
                delay(retry.delay)
                operation { load(recoveryStart(), isRecovery = true) }
            }
            return
        }
        fail(error)
    }

    private fun refreshCredentials(itemId: String, error: PlayerError, refresher: SourceRefresher) {
        credentialsRefreshed = true
        _state.update { it.copy(status = PlaybackStatus.Preparing) }
        emit(PlayerEventType.PlaybackError(error, isFinal = false))
        recoveryJob = scope.launch {
            val refreshed = try {
                refresher.refresh(itemId, error)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("SourceRefresher threw ${e::class.simpleName}; treating it as no new source")
                null
            }
            operation {
                if (isReleased || _state.value.currentItemId != itemId) return@operation
                if (refreshed == null) {
                    fail(error)
                } else {
                    replaceSource(itemId, refreshed)
                    emit(PlayerEventType.CredentialsRefreshed(CredentialsRefreshTrigger.Failure))
                    load(recoveryStart(), isRecovery = true)
                }
            }
        }
    }

    private fun fail(error: PlayerError) {
        _engine.value?.unload()
        resetLoadState()
        _state.update { it.copy(status = PlaybackStatus.Error, error = error, isSeeking = false) }
        emit(PlayerEventType.PlaybackError(error, isFinal = true))
    }

    // endregion

    // region Internals

    private fun command(name: String, block: () -> Unit) = operation {
        if (isReleased) log("$name ignored: player is released") else block()
    }

    /**
     * Runs [block] now, or after the current operation if called re-entrantly.
     * Derived effects (engine play intent, PlaybackStarted, the progress ticker) are reconciled after each block.
     * Observers on the main thread can react to a state change while it is being made and call back in; those
     * calls run only after the change is reconciled, so they never skip the events of a state they observed.
     */
    private fun operation(block: () -> Unit) {
        if (inOperation) {
            deferred.addLast(block)
            return
        }
        inOperation = true
        try {
            block()
            if (!isReleased) reconcile()
            while (deferred.isNotEmpty()) {
                val next = deferred.removeFirst()
                if (isReleased) continue
                next()
                if (!isReleased) reconcile()
            }
        } finally {
            inOperation = false
        }
    }

    private fun reconcile() {
        val s = _state.value
        _engine.value?.setPlayWhenReady(
            s.playWhenReady && s.lifecycle == PlayerLifecycle.Active && preReadySeek == null && !s.isAudioInterrupted,
        )
        if (s.isPlaying && !startedForIntent) {
            startedForIntent = true
            emit(PlayerEventType.PlaybackStarted(isFirstStart = !hasStartedItem))
            hasStartedItem = true
        }
        updateProgressTicker(s.isPlaying)
        publishProgress()
        coordinator.onPlaybackChanged()
    }

    /** Loads the current item. A recovery reload keeps the first-frame state, error and retry counter. */
    private fun load(start: Duration, isRecovery: Boolean = false) {
        val item = currentItem ?: return
        val engine = ensureEngine()
        resetLoadState()
        lastPosition = start
        if (isRecovery) recoveryPosition = start else loadMark = timeSource.markNow()
        _state.update { it.copy(status = PlaybackStatus.Preparing, isSeeking = false) }
        engine.load(item.source, start)
    }

    /** A seek made while recovering wins over the position where the error happened. */
    private fun recoveryStart(): Duration = preReadySeek ?: lastPosition

    private fun reload(position: Duration, resetRetries: Boolean) {
        val start = preReadySeek ?: position
        cancelRecovery()
        if (resetRetries) {
            credentialsRefreshed = false
            _state.update { it.copy(retryAttempt = 0) }
        }
        load(start, isRecovery = true)
    }

    private fun resume() {
        retentionJob?.cancel()
        retentionJob = null
        _state.update { it.copy(lifecycle = PlayerLifecycle.Active) }
        emit(PlayerEventType.PlayerResumed)
        currentItem?.let { load(startPositionFor(it)) }
    }

    private fun suspendNow() {
        if (_state.value.lifecycle != PlayerLifecycle.Active) return
        suspendJob?.cancel()
        suspendJob = null
        rememberPosition()
        cancelRecovery()
        clearPlayIntent(PauseReason.Suspended)
        releaseEngine()
        releasePreparedItems()
        resetLoadState()
        _state.update {
            it.copy(
                lifecycle = PlayerLifecycle.Suspended,
                status = PlaybackStatus.Idle,
                isSeeking = false,
                isFirstFrameRendered = false,
            )
        }
        emit(PlayerEventType.PlayerSuspended)
        retentionJob = scope.launch {
            delay(configuration.lifecycle.positionRetention)
            if (ownerKey != null) {
                // Nothing reattached in time: the coordinator lets go of its controller.
                release()
            } else {
                operation {
                    positionMemory.clear()
                    lastPosition = currentItem?.startPosition ?: Duration.ZERO
                }
            }
        }
    }

    private fun restartFromBeginning() {
        val s = _state.value
        if (s.isSeekable) startSeek(s.seekableRange?.start ?: Duration.ZERO) else load(Duration.ZERO, isRecovery = true)
    }

    private fun startSeek(target: Duration) {
        val engine = _engine.value ?: return
        emit(PlayerEventType.SeekStarted(from = currentPosition(), to = target))
        seekInFlight = true
        lastPosition = target
        _state.update { it.copy(isSeeking = true) }
        engine.seekTo(target)
    }

    private fun unloadCurrentItem() {
        val previousId = _state.value.currentItemId
        cancelRecovery()
        _engine.value?.unload()
        resetLoadState()
        resetItemState()
        lastPosition = Duration.ZERO
        publishProgress()
        _state.update { it.withoutItemData().copy(currentItemId = null, status = PlaybackStatus.Idle) }
        emit(PlayerEventType.ItemChanged(previousId))
    }

    private fun setPlayIntent() {
        if (_state.value.playWhenReady) return
        startedForIntent = false
        _state.update { it.copy(playWhenReady = true, pauseReason = null) }
        coordinator.onPlayIntent(this)
    }

    /** Records why play intent was cleared. A user pause is never replaced by an automatic reason (§4). */
    private fun clearPlayIntent(reason: PauseReason) {
        val s = _state.value
        if (s.playWhenReady) {
            _state.update { it.copy(playWhenReady = false, pauseReason = reason) }
            emit(PlayerEventType.PlaybackPaused(reason))
        } else if (reason == PauseReason.User || s.pauseReason == null || s.pauseReason == PauseReason.Hold) {
            _state.update { it.copy(pauseReason = reason) }
        }
    }

    private fun rememberPosition() {
        val s = _state.value
        val id = s.currentItemId ?: return
        val position = currentPosition()
        val nearEnd = s.duration?.let { position >= it - EndDiscardWindow } ?: false
        if (s.status == PlaybackStatus.Ended || nearEnd) positionMemory.remove(id) else positionMemory[id] = position
    }

    private fun startPositionFor(item: MediaItem): Duration =
        positionMemory[item.id] ?: item.startPosition ?: Duration.ZERO

    private fun currentPosition(): Duration {
        preReadySeek?.let { return it }
        val engine = _engine.value
        if (engine != null && hasBeenReady && !seekInFlight && queuedSeek == null) lastPosition = engine.currentPosition
        return lastPosition
    }

    private fun publishProgress() {
        val position = currentPosition()
        val engine = _engine.value
        val buffered = if (engine != null && hasBeenReady) engine.bufferedPosition else position
        _progress.value = PlaybackProgress(position, buffered.coerceAtLeast(position))
    }

    private fun updateProgressTicker(isPlaying: Boolean) {
        if (!isPlaying) {
            progressJob?.cancel()
            progressJob = null
            return
        }
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (true) {
                delay(ProgressInterval)
                operation { onProgressTick() }
            }
        }
    }

    private fun onProgressTick() {
        val start = recoveryPosition ?: return
        if (currentPosition() >= start + RecoveryProgress) {
            recoveryPosition = null
            credentialsRefreshed = false
            _state.update { it.copy(retryAttempt = 0) }
        }
    }

    private fun replaceSource(itemId: String, source: MediaSource): Boolean {
        val index = items.indexOfFirst { it.id == itemId }
        if (index < 0) return false
        items = items.toMutableList().also { it[index] = it[index].copy(source = source) }
        return true
    }

    /**
     * Parks the current engine instead of unloading it, if its item has played and nothing is pending.
     * The oldest prepared items beyond [LifecycleConfig.keepPreparedItems] are released.
     */
    private fun keepCurrentItemPrepared(itemId: String?) {
        val engine = _engine.value ?: return
        val item = itemId?.let { id -> items.firstOrNull { it.id == id } } ?: return
        val s = _state.value
        val keep = configuration.lifecycle.keepPreparedItems
        val settled = hasBeenReady && !seekInFlight && queuedSeek == null &&
            (s.status == PlaybackStatus.Ready || s.status == PlaybackStatus.Buffering)
        if (keep == 0 || s.lifecycle != PlayerLifecycle.Active || !settled) return

        _engine.value = null
        val prepared = PreparedItem(
            itemId = item.id,
            engine = engine,
            source = item.source,
            status = if (s.status == PlaybackStatus.Ready) EngineStatus.Ready else EngineStatus.Buffering,
            timeline = EngineTimeline(s.duration, isLive = s.isLive, isSeekable = s.isSeekable),
            videoSize = s.videoSize,
            hasStartedItem = hasStartedItem,
        )
        engine.setListener(prepared)
        engine.setParked(true)
        preparedItems[item.id] = prepared
        while (preparedItems.size > keep) {
            val oldest = preparedItems.keys.first()
            releasePreparedItems { id, _ -> id == oldest }
        }
    }

    /** Removes and returns the prepared engine for [item], if it is still usable. */
    private fun takePreparedItem(item: MediaItem): PreparedItem? {
        val prepared = preparedItems.remove(item.id) ?: return null
        if (prepared.source == item.source && _state.value.lifecycle == PlayerLifecycle.Active) return prepared
        prepared.engine.setListener(null)
        prepared.engine.release()
        return null
    }

    /** Makes a kept item current again: no re-prepare, same position, playback resumes right away. */
    private fun activatePrepared(prepared: PreparedItem) {
        releaseEngine()
        val engine = prepared.engine
        _engine.value = engine
        engine.setListener(Callbacks(engine))
        engine.setVolume(effectiveVolume())
        engine.setPlaybackSpeed(_state.value.playbackSpeed)
        engine.setParked(false)
        hasBeenReady = true
        hasStartedItem = prepared.hasStartedItem
        loadMark = timeSource.markNow()
        lastPosition = engine.currentPosition
        prepared.timeline?.let(::onEngineTimeline)
        _state.update {
            it.copy(
                status = if (prepared.status == EngineStatus.Ready) PlaybackStatus.Ready else PlaybackStatus.Buffering,
                videoSize = prepared.videoSize,
            )
        }
    }

    private fun releasePreparedItems(filter: (String, PreparedItem) -> Boolean = { _, _ -> true }) {
        val iterator = preparedItems.entries.iterator()
        while (iterator.hasNext()) {
            val (id, prepared) = iterator.next()
            if (!filter(id, prepared)) continue
            iterator.remove()
            prepared.engine.setListener(null)
            prepared.engine.release()
        }
    }

    /** A parked engine. It tracks what the engine reports so the item can be restored without re-preparing. */
    private inner class PreparedItem(
        val itemId: String,
        val engine: PlaybackEngine,
        val source: MediaSource,
        var status: EngineStatus,
        var timeline: EngineTimeline?,
        var videoSize: IntSize?,
        val hasStartedItem: Boolean,
    ) : EngineListener {
        override fun onStatusChanged(status: EngineStatus) {
            this.status = status
        }

        override fun onTimelineChanged(timeline: EngineTimeline) {
            this.timeline = timeline
        }

        override fun onSeekCompleted() = Unit

        override fun onFirstFrameRendered() = Unit

        override fun onVideoSizeChanged(size: IntSize) {
            videoSize = size
        }

        // A kept item that fails is simply dropped; selecting it again prepares it from scratch.
        override fun onError(error: PlayerError) = operation {
            releasePreparedItems { id, prepared -> id == itemId && prepared === this }
        }
    }

    private fun ensureEngine(): PlaybackEngine = _engine.value ?: run {
        coordinator.ensureCapacity(this)
        engineFactory()
    }.also { engine ->
        _engine.value = engine
        engine.setListener(Callbacks(engine))
        engine.setVolume(effectiveVolume())
        engine.setPlaybackSpeed(_state.value.playbackSpeed)
    }

    private fun releaseEngine() {
        val engine = _engine.value ?: return
        _engine.value = null
        engine.setListener(null)
        engine.release()
    }

    private fun applyVolume() {
        _engine.value?.setVolume(effectiveVolume())
    }

    private fun effectiveVolume(): Float = _state.value.let { if (it.isMuted) 0f else it.volume }

    private fun cancelRecovery() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun resetLoadState() {
        hasBeenReady = false
        preReadySeek = null
        seekInFlight = false
        queuedSeek = null
        stallMark = null
    }

    private fun resetItemState() {
        hasStartedItem = false
        startedForIntent = false
        credentialsRefreshed = false
        recoveryPosition = null
        loadMark = null
    }

    private fun PlayerState.withoutItemData(): PlayerState = copy(
        duration = null,
        seekableRange = null,
        isSeekable = false,
        isLive = false,
        isSeeking = false,
        isFirstFrameRendered = false,
        videoSize = null,
        error = null,
        retryAttempt = 0,
    )

    private fun emit(type: PlayerEventType) {
        val event = newEvent(type)
        _events.tryEmit(event)
        coordinator.forward(event)
    }

    // endregion

    // region Coordinator

    fun newEvent(type: PlayerEventType): PlayerEvent {
        val itemId = _state.value.currentItemId
        return PlayerEvent(
            playerId = id,
            itemId = itemId,
            position = if (itemId != null) currentPosition() else null,
            timestamp = clock.now(),
            type = type,
        )
    }

    /** Another player became the active one (§14). A hold in progress is replaced, so releasing it doesn't resume. */
    fun pauseForCoordinator() = operation {
        if (!isReleased && _state.value.isPlayingOrHeld) clearPlayIntent(PauseReason.Coordinator)
    }

    fun setFullscreenAvailable(available: Boolean) = operation {
        if (!isReleased) _state.update { it.copy(isFullscreenAvailable = available) }
    }

    /** Another player took the fullscreen host. */
    fun leaveFullscreen() = operation {
        if (!isReleased && _state.value.presentation == Presentation.Fullscreen) setPresentation(Presentation.Inline)
    }

    /** A call, alarm or transient focus loss (§13). Playback resumes when it ends, if the system allows. */
    fun beginInterruption() = operation {
        if (isReleased) return@operation
        if (_state.value.isPlayingOrHeld) {
            resumeAfterInterruption = true
            clearPlayIntent(PauseReason.Interruption)
        }
        _state.update { it.copy(isAudioInterrupted = true) }
    }

    fun endInterruption(shouldResume: Boolean) = operation {
        if (isReleased) return@operation
        _state.update { it.copy(isAudioInterrupted = false) }
        // A user pause or another automatic pause in the meantime wins.
        if (shouldResume && resumeAfterInterruption && _state.value.pauseReason == PauseReason.Interruption) setPlayIntent()
        resumeAfterInterruption = false
    }

    /** Permanent focus loss or headphones disconnected: pause without resuming. */
    fun loseAudio() = operation {
        if (isReleased) return@operation
        resumeAfterInterruption = false
        _state.update { it.copy(isAudioInterrupted = false) }
        if (_state.value.isPlayingOrHeld) clearPlayIntent(PauseReason.Interruption)
    }

    fun enterBackground() = operation {
        if (isReleased || !_state.value.isPlayingOrHeld) return@operation
        resumeAfterBackground = true
        clearPlayIntent(PauseReason.Background)
    }

    /** Resumes only with [LifecycleConfig.resumeAfterBackground], and only if nothing else paused it meanwhile. */
    fun enterForeground() = operation {
        if (isReleased) return@operation
        val resume = resumeAfterBackground && configuration.lifecycle.resumeAfterBackground &&
            _state.value.pauseReason == PauseReason.Background
        resumeAfterBackground = false
        if (resume) setPlayIntent()
    }

    private val PlayerState.isPlayingOrHeld: Boolean
        get() = playWhenReady || pauseReason == PauseReason.Hold

    private fun setPresentation(presentation: Presentation) {
        val from = _state.value.presentation
        _state.update { it.copy(presentation = presentation) }
        emit(PlayerEventType.PresentationChanged(from, presentation))
    }

    /** Frees items kept for instant switch-back, to stay within the coordinator's cap. */
    fun dropPreparedItems() {
        releasePreparedItems()
    }

    init {
        // Last, once every property is initialized.
        coordinator.register(this)
        // An owned controller that never gets a surface is suspended and released like one scrolled away.
        if (ownerKey != null) scheduleSuspend()
    }

    // endregion

    private companion object {
        val ProgressInterval = 250.milliseconds
        val SuspendDebounce = 1.seconds
        val EndDiscardWindow = 1.seconds
        val RecoveryProgress = 1.seconds
        const val EventBufferSize = 64
    }
}

private var playerCount = 0

private fun nextPlayerId(): String = "player-${++playerCount}"
