package co.liebi.videoplayer.core.internal

import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.CoordinatorConfig
import co.liebi.videoplayer.core.CredentialsRefreshTrigger
import co.liebi.videoplayer.core.ErrorCategory
import co.liebi.videoplayer.core.LifecycleConfig
import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackConfig
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerEventType
import co.liebi.videoplayer.core.PlayerEventType.BufferingEnded
import co.liebi.videoplayer.core.PlayerEventType.BufferingStarted
import co.liebi.videoplayer.core.PlayerEventType.PlaybackPaused
import co.liebi.videoplayer.core.PlayerEventType.PlaybackStarted
import co.liebi.videoplayer.core.PlayerEventType.SeekCompleted
import co.liebi.videoplayer.core.PlayerEventType.SeekStarted
import co.liebi.videoplayer.core.PlayerLifecycle
import co.liebi.videoplayer.core.SourceRefresher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlayerControllerTest {

    // region Play intent and status (§4)

    @Test
    fun playbackStartsOnlyOnceReady() = playerTest {
        it.selectA()
        assertEquals(PlaybackStatus.Preparing, it.state.status)
        assertTrue(it.state.playWhenReady)
        assertFalse(it.state.isPlaying)

        it.engine.becomeReady()

        assertEquals(PlaybackStatus.Ready, it.state.status)
        assertTrue(it.state.isPlaying)
        assertTrue(it.engine.playWhenReady)
        assertEquals(listOf(PlaybackStarted(isFirstStart = true)), it.eventsOf<PlaybackStarted>())
    }

    @Test
    fun pauseDuringPreparingNeverStartsPlayback() = playerTest {
        it.selectA()
        it.controller.pause()
        it.engine.becomeReady()

        assertEquals(PlaybackStatus.Ready, it.state.status)
        assertFalse(it.state.isPlaying)
        assertFalse(it.engine.playWhenReady)
        assertEquals(PauseReason.User, it.state.pauseReason)
        assertEquals(listOf(PlaybackPaused(PauseReason.User)), it.eventsOf<PlaybackPaused>())
        assertTrue(it.eventsOf<PlaybackStarted>().isEmpty())
    }

    @Test
    fun stallResumesOnItsOwnAndEmitsBufferingEvents() = playerTest {
        it.selectA()
        it.engine.becomeReady()

        it.engine.stall()
        assertEquals(PlaybackStatus.Buffering, it.state.status)
        assertTrue(it.state.playWhenReady)
        advanceTimeBy(3.seconds)
        it.engine.resumeFromStall()

        assertTrue(it.state.isPlaying)
        assertEquals(listOf(BufferingStarted), it.eventsOf<BufferingStarted>())
        assertEquals(listOf(BufferingEnded(3.seconds)), it.eventsOf<BufferingEnded>())
        assertEquals(1, it.eventsOf<PlaybackStarted>().size, "a stall ending is not a new start")
    }

    @Test
    fun userPauseIsNeverReplacedByAnAutomaticReason() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.controller.pause()
        it.controller.suspend()

        assertEquals(PauseReason.User, it.state.pauseReason)
        assertEquals(listOf(PlaybackPaused(PauseReason.User)), it.eventsOf<PlaybackPaused>())
    }

    @Test
    fun withoutAutoReplayEndClearsIntentAndPlayRestartsFromTheStart() = playerTest(
        PlayerConfiguration(playback = PlaybackConfig(autoReplay = false)),
    ) {
        it.selectA()
        it.engine.becomeReady()
        it.engine.end()

        assertEquals(PlaybackStatus.Ended, it.state.status)
        assertFalse(it.state.playWhenReady)
        assertEquals(PauseReason.Ended, it.state.pauseReason)
        assertEquals(
            listOf(PlayerEventType.PlaybackCompleted, PlaybackPaused(PauseReason.Ended)),
            it.eventTypes.takeLast(2),
        )

        it.controller.play()
        assertEquals(listOf(Duration.ZERO), it.engine.seeks)
        it.engine.completeSeek()
        assertTrue(it.state.isPlaying)
        assertEquals(PlaybackStarted(isFirstStart = false), it.eventsOf<PlaybackStarted>().last())
    }

    @Test
    fun autoReplayIsOnByDefaultAndLoopsWithoutClearingIntent() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.end()

        assertTrue(it.state.playWhenReady)
        assertEquals(listOf(Duration.ZERO), it.engine.seeks)
        assertEquals(1, it.eventsOf<PlayerEventType.PlaybackCompleted>().size)
        assertTrue(it.eventsOf<PlaybackPaused>().isEmpty())
    }

    @Test
    fun autoReplayCanBeTurnedOffAtRuntime() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.controller.setAutoReplay(false)
        assertFalse(it.state.autoReplay)

        it.engine.end()

        assertEquals(PlaybackStatus.Ended, it.state.status)
        assertTrue(it.engine.seeks.isEmpty())
        assertEquals(PlaybackPaused(PauseReason.Ended), it.eventsOf<PlaybackPaused>().single())
    }

    @Test
    fun holdPausesAndReleaseResumes() = playerTest {
        it.selectA()
        it.engine.becomeReady()

        it.controller.beginHold()
        assertFalse(it.state.playWhenReady)
        assertEquals(PauseReason.Hold, it.state.pauseReason)
        assertFalse(it.engine.playWhenReady)

        it.controller.endHold()
        assertTrue(it.state.isPlaying)
        assertEquals(
            listOf(PlaybackPaused(PauseReason.Hold)),
            it.eventsOf<PlaybackPaused>(),
        )
        assertEquals(PlaybackStarted(isFirstStart = false), it.eventsOf<PlaybackStarted>().last())
    }

    @Test
    fun holdOnAPausedPlayerDoesNothingAndReleaseNeverStartsPlayback() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.controller.pause()

        it.controller.beginHold()
        it.controller.endHold()

        assertFalse(it.state.playWhenReady)
        assertEquals(PauseReason.User, it.state.pauseReason)
    }

    @Test
    fun aUserPauseDuringAHoldWins() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.controller.beginHold()
        it.controller.pause()

        it.controller.endHold()

        assertFalse(it.state.playWhenReady)
        assertEquals(PauseReason.User, it.state.pauseReason)
    }

    @Test
    fun commandsFromEventCollectorsAreSerialized() = playerTest {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            it.controller.events.collect { event -> if (event.type is PlaybackStarted) it.controller.pause() }
        }
        it.selectA()
        it.engine.becomeReady()

        assertFalse(it.state.playWhenReady)
        assertFalse(it.engine.playWhenReady)
        assertEquals(listOf(PlaybackPaused(PauseReason.User)), it.eventsOf<PlaybackPaused>())
    }

    // endregion

    // region Seeking (§6)

    @Test
    fun rapidSeeksCoalesceToTheLatestTarget() = playerTest {
        it.selectA()
        it.engine.becomeReady()

        it.controller.seekTo(10.seconds)
        it.controller.seekTo(20.seconds)
        it.controller.seekTo(30.seconds)
        assertEquals(listOf(10.seconds), it.engine.seeks)
        assertTrue(it.state.isSeeking)
        assertEquals(30.seconds, it.controller.progress.value.position)

        it.engine.completeSeek()
        assertEquals(listOf(10.seconds, 30.seconds), it.engine.seeks)
        assertTrue(it.state.isSeeking)

        it.engine.completeSeek()
        assertFalse(it.state.isSeeking)
        assertTrue(it.state.playWhenReady, "seeking never changes play intent")
        assertEquals(
            listOf(
                SeekStarted(Duration.ZERO, 10.seconds),
                SeekCompleted(10.seconds),
                SeekStarted(10.seconds, 30.seconds),
                SeekCompleted(30.seconds),
            ),
            it.eventTypes.filter { type -> type is SeekStarted || type is SeekCompleted },
        )
    }

    @Test
    fun seekIsClampedToTheSeekableRange() = playerTest {
        it.selectA()
        it.engine.becomeReady(duration = 60.seconds)
        it.controller.seekTo(90.seconds)
        assertEquals(listOf(60.seconds), it.engine.seeks)
    }

    @Test
    fun seekIsIgnoredWhenMediaIsNotSeekable() = playerTest {
        it.selectA()
        it.engine.becomeReady(duration = null, isSeekable = false, isLive = true)
        it.controller.seekTo(10.seconds)

        assertTrue(it.engine.seeks.isEmpty())
        assertTrue(it.state.isLive)
        assertNull(it.state.duration)
        assertFalse(it.state.isSeekable)
    }

    @Test
    fun seekBeforeReadyIsAppliedOnceReady() = playerTest {
        it.selectA()
        it.controller.seekTo(20.seconds)
        assertTrue(it.engine.seeks.isEmpty())
        assertFalse(it.engine.playWhenReady, "must not play from the old position")
        assertEquals(20.seconds, it.controller.progress.value.position)

        it.engine.becomeReady()
        assertEquals(listOf(20.seconds), it.engine.seeks)
        assertEquals(PlaybackStatus.Preparing, it.state.status)

        it.engine.completeSeek()
        assertEquals(PlaybackStatus.Ready, it.state.status)
        assertTrue(it.state.isPlaying)
    }

    // endregion

    // region Playlist and position memory (§7)

    @Test
    fun switchingItemsResumesEachAtItsRememberedPosition() = playerTest(NoPreparedItems) {
        it.selectA()
        it.engine.becomeReady()
        it.engine.currentPosition = 15.seconds

        it.controller.selectItem("b")
        assertEquals(itemB.source to Duration.ZERO, it.engine.loads.last())
        assertEquals(PlayerEventType.ItemChanged(previousItemId = "a"), it.eventsOf<PlayerEventType.ItemChanged>().last())
        it.engine.becomeReady()
        it.engine.currentPosition = 5.seconds

        it.controller.selectItem("a")
        assertEquals(itemA.source to 15.seconds, it.engine.loads.last())
        it.controller.selectItem("b")
        assertEquals(itemB.source to 5.seconds, it.engine.loads.last())
    }

    @Test
    fun positionNearTheEndIsDiscardedAndStartPositionIsUsed() = playerTest(NoPreparedItems) {
        val itemC = MediaItem("c", MediaSource.File("/c.mp4"), startPosition = 7.seconds)
        it.controller.setItems(listOf(itemA, itemC))
        it.controller.selectItem("c")
        assertEquals(itemC.source to 7.seconds, it.engine.loads.last())

        it.engine.becomeReady(duration = 60.seconds)
        it.engine.currentPosition = 59.5.seconds
        it.controller.selectItem("a")
        it.controller.selectItem("c")
        assertEquals(itemC.source to 7.seconds, it.engine.loads.last())
    }

    @Test
    fun setItemsRejectsDuplicateIds() = playerTest {
        assertFailsWith<IllegalArgumentException> { it.controller.setItems(listOf(itemA, itemA)) }
    }

    @Test
    fun removingTheCurrentItemStopsThePlayer() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.controller.setItems(listOf(itemB))

        assertEquals(PlaybackStatus.Idle, it.state.status)
        assertNull(it.state.currentItemId)
        assertEquals(1, it.engine.unloadCount)
        assertEquals(PlayerEventType.ItemChanged(previousItemId = "a"), it.eventsOf<PlayerEventType.ItemChanged>().last())
    }

    @Test
    fun firstFrameResetsOnItemChange() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.reportVideoSize(IntSize(1920, 1080))
        advanceTimeBy(400.milliseconds)
        it.engine.renderFirstFrame()
        assertTrue(it.state.isFirstFrameRendered)
        assertEquals(IntSize(1920, 1080), it.state.videoSize)
        assertEquals(
            listOf(PlayerEventType.FirstFrameRendered(400.milliseconds)),
            it.eventsOf<PlayerEventType.FirstFrameRendered>(),
        )

        it.controller.selectItem("b")
        assertFalse(it.state.isFirstFrameRendered)
        assertNull(it.state.videoSize)
    }

    // endregion

    // region Prepared items

    @Test
    fun switchingBackToARecentItemIsInstant() = playerTest {
        it.selectA()
        val engineA = it.engine
        engineA.becomeReady()
        engineA.currentPosition = 15.seconds

        it.controller.selectItem("b")
        val engineB = it.engine
        assertTrue(engineA.isParked)
        assertFalse(engineA.playWhenReady)
        assertFalse(engineA.isReleased)
        assertTrue(engineA !== engineB)
        engineB.becomeReady()

        it.controller.selectItem("a")
        assertSame(engineA, it.controller.engine.value)
        assertEquals(1, engineA.loads.size, "the kept item is not prepared again")
        assertFalse(engineA.isParked)
        assertEquals(PlaybackStatus.Ready, it.state.status)
        assertTrue(it.state.isPlaying)
        assertEquals(15.seconds, it.controller.progress.value.position)
        assertEquals(PlaybackStarted(isFirstStart = false), it.eventsOf<PlaybackStarted>().last())
        assertTrue(engineB.isParked, "the item switched away from is kept in turn")
    }

    @Test
    fun onlyTheConfiguredNumberOfItemsStayPrepared() = playerTest {
        val itemC = MediaItem("c", MediaSource.Url("https://example.com/c.mp4"))
        it.controller.setItems(listOf(itemA, itemB, itemC))
        it.controller.selectItem("a")
        val engineA = it.engine
        engineA.becomeReady()
        it.controller.selectItem("b")
        val engineB = it.engine
        engineB.becomeReady()

        it.controller.selectItem("c")

        assertTrue(engineA.isReleased, "the oldest kept item is released")
        assertTrue(engineB.isParked && !engineB.isReleased)
    }

    @Test
    fun keepingPreparedItemsCanBeTurnedOff() = playerTest(NoPreparedItems) {
        it.selectA()
        it.engine.becomeReady()
        it.controller.selectItem("b")

        assertEquals(1, it.engines.size, "the single engine is reused")
        assertEquals(itemB.source to Duration.ZERO, it.engine.loads.last())
    }

    @Test
    fun aKeptItemThatFailsIsDropped() = playerTest {
        it.selectA()
        val engineA = it.engine
        engineA.becomeReady()
        it.controller.selectItem("b")
        it.engine.becomeReady()

        engineA.fail(networkError)
        assertTrue(engineA.isReleased)

        it.controller.selectItem("a")
        assertEquals(3, it.engines.size)
        assertEquals(itemA.source to Duration.ZERO, it.engine.loads.single())
        assertEquals(PlaybackStatus.Preparing, it.state.status)
    }

    @Test
    fun itemsThatNeverPlayedAreNotKept() = playerTest {
        it.selectA()
        val engineA = it.engine
        it.controller.selectItem("b")

        assertFalse(engineA.isParked)
        assertSame(engineA, it.engine, "the engine is reused for the next item")
    }

    @Test
    fun keptItemsAreReleasedOnSuspendAndWhenTheirSourceChanges() = playerTest {
        it.selectA()
        val engineA = it.engine
        engineA.becomeReady()
        it.controller.selectItem("b")
        val engineB = it.engine
        engineB.becomeReady()

        it.controller.updateSource("a", MediaSource.Url("https://example.com/a.m3u8?token=new"))
        assertTrue(engineA.isReleased)

        it.controller.selectItem("a")
        it.engine.becomeReady()
        it.controller.suspend()
        assertTrue(engineB.isReleased)
    }

    // endregion

    // region Errors and retry (§9)

    @Test
    fun recoverableErrorsRetryAtTheLastPositionThenFail() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.currentPosition = 12.seconds

        repeat(3) { attempt ->
            it.engine.fail(networkError)
            assertEquals(PlaybackStatus.Preparing, it.state.status)
            assertEquals(attempt + 1, it.state.retryAttempt)
            assertEquals(
                PlayerEventType.RetryScheduled(attempt + 1, 2.seconds, networkError),
                it.eventsOf<PlayerEventType.RetryScheduled>().last(),
            )
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(itemA.source to 12.seconds, it.engine.loads.last())
        }

        it.engine.fail(networkError)
        assertEquals(PlaybackStatus.Error, it.state.status)
        assertEquals(networkError, it.state.error)
        assertEquals(PlayerEventType.PlaybackError(networkError, isFinal = true), it.eventTypes.last())

        it.controller.retry()
        assertEquals(0, it.state.retryAttempt)
        assertEquals(PlaybackStatus.Preparing, it.state.status)
        assertEquals(itemA.source to 12.seconds, it.engine.loads.last())
    }

    @Test
    fun permanentErrorsAreNotRetried() = playerTest {
        it.selectA()
        it.engine.fail(notFoundError)

        assertEquals(PlaybackStatus.Error, it.state.status)
        assertTrue(it.eventsOf<PlayerEventType.RetryScheduled>().isEmpty())
        assertEquals(1, it.engine.unloadCount)

        it.controller.play()
        assertEquals(PlaybackStatus.Preparing, it.state.status)
        assertEquals(2, it.engine.loads.size)
    }

    @Test
    fun retryCounterResetsOncePlaybackProgresses() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.fail(networkError)
        advanceTimeBy(2.seconds)
        runCurrent()
        it.engine.becomeReady()
        assertEquals(1, it.state.retryAttempt)

        it.engine.currentPosition = 2.seconds
        advanceTimeBy(300.milliseconds)
        assertEquals(0, it.state.retryAttempt)
    }

    @Test
    fun credentialFailureRefreshesOnceThenFails() {
        val refreshed = MediaSource.Url("https://example.com/a.m3u8?token=new")
        var calls = 0
        playerTest(refresher = { _, _ -> calls++; refreshed }) {
            it.selectA()
            it.engine.becomeReady()
            it.engine.currentPosition = 30.seconds

            it.engine.fail(forbiddenError)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(refreshed to 30.seconds, it.engine.loads.last())
            assertEquals(
                PlayerEventType.CredentialsRefreshed(CredentialsRefreshTrigger.Failure),
                it.eventsOf<PlayerEventType.CredentialsRefreshed>().single(),
            )

            it.engine.fail(forbiddenError)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(PlaybackStatus.Error, it.state.status)
            assertEquals(ErrorCategory.AccessDenied, it.state.error?.category)
        }
    }

    @Test
    fun refresherReturningNullFailsWithAccessDenied() = playerTest(refresher = { _, _ -> null }) {
        it.selectA()
        it.engine.fail(forbiddenError)
        runCurrent()

        assertEquals(PlaybackStatus.Error, it.state.status)
        assertEquals(1, it.engine.loads.size)
    }

    @Test
    fun updateSourceReloadsTheCurrentItemAtItsPosition() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.currentPosition = 8.seconds
        val updated = MediaSource.Url("https://example.com/a.m3u8?token=fresh")

        it.controller.updateSource("a", updated)

        assertEquals(updated to 8.seconds, it.engine.loads.last())
        assertTrue(it.state.playWhenReady)
        assertEquals(
            PlayerEventType.CredentialsRefreshed(CredentialsRefreshTrigger.Update),
            it.eventsOf<PlayerEventType.CredentialsRefreshed>().single(),
        )
    }

    // endregion

    // region Lifecycle (§8)

    @Test
    fun suspendReleasesTheEngineAndPlayResumesAtThePosition() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.currentPosition = 20.seconds
        val first = it.engine

        it.controller.suspend()
        assertTrue(first.isReleased)
        assertEquals(PlayerLifecycle.Suspended, it.state.lifecycle)
        assertEquals(PlaybackStatus.Idle, it.state.status)
        assertEquals(PauseReason.Suspended, it.state.pauseReason)
        assertEquals(
            listOf(PlaybackPaused(PauseReason.Suspended), PlayerEventType.PlayerSuspended),
            it.eventTypes.takeLast(2),
        )

        it.controller.play()
        assertEquals(2, it.engines.size)
        assertEquals(itemA.source to 20.seconds, it.engine.loads.single())
        assertEquals(PlayerLifecycle.Active, it.state.lifecycle)
        assertTrue(it.state.playWhenReady)
        assertTrue(PlayerEventType.PlayerResumed in it.eventTypes)
    }

    @Test
    fun positionsAreDroppedAfterTheRetentionPeriod() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.currentPosition = 20.seconds
        it.controller.suspend()

        advanceTimeBy(31.seconds)
        it.controller.play()
        assertEquals(itemA.source to Duration.ZERO, it.engine.loads.single())
    }

    @Test
    fun lastSurfaceDetachingSuspendsAfterADebounce() = playerTest {
        val first = Any()
        val second = Any()
        it.controller.attachSurface(first)
        it.selectA()
        it.engine.becomeReady()

        it.controller.attachSurface(second)
        assertSame(second, it.controller.activeSurface.value)
        it.controller.detachSurface(second)
        assertSame(first, it.controller.activeSurface.value)

        it.controller.detachSurface(first)
        advanceTimeBy(500.milliseconds)
        it.controller.attachSurface(second)
        advanceTimeBy(2.seconds)
        assertEquals(PlayerLifecycle.Active, it.state.lifecycle, "re-attaching within the debounce keeps the player")

        it.controller.detachSurface(second)
        advanceTimeBy(1100.milliseconds)
        assertEquals(PlayerLifecycle.Suspended, it.state.lifecycle)

        it.controller.attachSurface(first)
        assertEquals(PlayerLifecycle.Active, it.state.lifecycle)
        assertFalse(it.state.playWhenReady, "resuming from a surface attach stays paused")
        assertEquals(2, it.engines.size)
    }

    @Test
    fun releaseIsFinalAndIdempotent() = playerTest {
        it.selectA()
        it.controller.release()
        it.controller.release()
        it.controller.play()

        assertEquals(PlayerLifecycle.Released, it.state.lifecycle)
        assertTrue(it.engine.isReleased)
        assertEquals(PlayerEventType.PlayerReleased, it.eventTypes.last())
        assertEquals(listOf("play ignored: player is released"), it.warnings)
    }

    // endregion

    // region Progress, volume and speed

    @Test
    fun progressTicksOnlyWhilePlaying() = playerTest {
        it.selectA()
        it.engine.becomeReady()
        it.engine.currentPosition = 1.seconds
        advanceTimeBy(260.milliseconds)
        assertEquals(1.seconds, it.controller.progress.value.position)

        it.engine.currentPosition = 2.seconds
        it.controller.pause()
        assertEquals(2.seconds, it.controller.progress.value.position)

        it.engine.currentPosition = 5.seconds
        advanceTimeBy(1.seconds)
        assertEquals(2.seconds, it.controller.progress.value.position)
    }

    @Test
    fun muteAndVolumeAreAppliedAsPlayerGain() = playerTest {
        it.selectA()
        it.controller.setVolume(0.5f)
        assertEquals(0.5f, it.engine.volume)
        it.controller.setMuted(true)
        assertEquals(0f, it.engine.volume)
        it.controller.setMuted(false)
        assertEquals(0.5f, it.engine.volume)

        it.controller.setPlaybackSpeed(3f)
        assertEquals(2f, it.state.playbackSpeed)
        assertEquals(2f, it.engine.speed)
    }

    // endregion

    private fun playerTest(
        configuration: PlayerConfiguration = PlayerConfiguration(),
        refresher: SourceRefresher? = null,
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val harness = Harness(this, configuration, refresher)
        try {
            body(harness)
        } finally {
            harness.controller.release()
        }
    }

    private fun Harness.selectA() {
        controller.setItems(listOf(itemA, itemB))
        controller.selectItem("a")
    }

    private inner class Harness(
        scope: TestScope,
        configuration: PlayerConfiguration,
        refresher: SourceRefresher?,
    ) {
        val engines = mutableListOf<FakePlaybackEngine>()
        val warnings = mutableListOf<String>()
        val events = mutableListOf<PlayerEvent>()

        val controller = DefaultPlayerController(
            configuration = configuration,
            sourceRefresher = refresher,
            engineFactory = { FakePlaybackEngine().also { engines += it } },
            coordinator = PlayerCoordinator(CoordinatorConfig(), audioSession = { }, log = { warnings += it }),
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = FixedClock,
            timeSource = scope.testScheduler.timeSource,
            log = { warnings += it },
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                controller.events.collect { events += it }
            }
        }

        val engine: FakePlaybackEngine get() = engines.last()
        val state get() = controller.state.value
        val eventTypes: List<PlayerEventType> get() = events.map { it.type }

        inline fun <reified T : PlayerEventType> eventsOf(): List<T> = eventTypes.filterIsInstance<T>()
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }

    private companion object {
        val itemA = MediaItem("a", MediaSource.Url("https://example.com/a.m3u8"))
        val itemB = MediaItem("b", MediaSource.Url("https://example.com/b.mp4"))
        val networkError = ErrorClassifier.of(ErrorCategory.Network, null)
        val notFoundError = ErrorClassifier.fromHttpStatus(404, null)
        val forbiddenError = ErrorClassifier.fromHttpStatus(403, null)
        val NoPreparedItems = PlayerConfiguration(lifecycle = LifecycleConfig(keepPreparedItems = 0))
    }
}
