package co.liebi.videoplayer.core.internal

import co.liebi.videoplayer.core.CoordinatorConfig
import co.liebi.videoplayer.core.LifecycleConfig
import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackConfig
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerEventType
import co.liebi.videoplayer.core.PlayerLifecycle
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
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerCoordinatorTest {

    // region Registry and events

    @Test
    fun mergesEventsOfAllPlayersIncludingRegistration() = coordinatorTest {
        val a = it.player(Idle)
        val b = it.player()
        a.load("x")
        a.controller.release()

        val events = it.events.map { event -> event.playerId to event.type }
        assertEquals(
            listOf(
                a.controller.id to PlayerEventType.PlayerRegistered,
                b.controller.id to PlayerEventType.PlayerRegistered,
                a.controller.id to PlayerEventType.ItemChanged(previousItemId = null),
                a.controller.id to PlayerEventType.PlayerReleased,
                a.controller.id to PlayerEventType.PlayerUnregistered,
            ),
            events,
        )
        assertEquals(listOf(b.controller), it.coordinator.players.value)
    }

    @Test
    fun releaseAllReleasesEveryPlayer() = coordinatorTest {
        val a = it.player()
        val b = it.player()
        a.load("x")

        it.coordinator.releaseAll()

        assertEquals(PlayerLifecycle.Released, a.controller.state.value.lifecycle)
        assertEquals(PlayerLifecycle.Released, b.controller.state.value.lifecycle)
        assertTrue(a.engine.isReleased)
        assertTrue(it.coordinator.players.value.isEmpty())
    }

    // endregion

    // region Single active player

    @Test
    fun playingOnePlayerPausesTheOthersWhenSingleActive() = coordinatorTest(CoordinatorConfig(singleActivePlayer = true)) {
        val a = it.player()
        val b = it.player()
        a.load("x")
        a.controller.play()
        b.load("y")

        b.controller.play()

        assertFalse(a.controller.state.value.playWhenReady)
        assertEquals(PauseReason.Coordinator, a.controller.state.value.pauseReason)
        assertTrue(PlayerEventType.PlaybackPaused(PauseReason.Coordinator) in it.eventsOf(a))
        assertTrue(b.controller.state.value.isPlaying)

        b.controller.pause()
        assertFalse(a.controller.state.value.playWhenReady, "a player paused by the coordinator does not resume")
    }

    @Test
    fun selectingAnItemThatAutoplaysAlsoPausesTheOthers() = coordinatorTest(CoordinatorConfig(singleActivePlayer = true)) {
        val a = it.player()
        val b = it.player(PlayerConfiguration(lifecycle = LifecycleConfig(keepPreparedItems = 0)))
        a.load("x")
        a.controller.play()

        b.load("y")

        assertEquals(PauseReason.Coordinator, a.controller.state.value.pauseReason)
        assertTrue(b.controller.state.value.playWhenReady)
    }

    @Test
    fun playersPlayTogetherByDefault() = coordinatorTest {
        val a = it.player()
        val b = it.player()
        a.load("x")
        b.load("y")
        a.controller.play()
        b.controller.play()

        assertTrue(a.controller.state.value.isPlaying)
        assertTrue(b.controller.state.value.isPlaying)
    }

    // endregion

    // region Native player cap

    @Test
    fun capDropsKeptPreparedItemsFirst() = coordinatorTest(CoordinatorConfig(maxActivePlayers = 2)) {
        val a = it.player(PlayerConfiguration(lifecycle = LifecycleConfig(keepPreparedItems = 1)))
        val b = it.player()
        a.controller.setItems(listOf(item("x"), item("y")))
        a.controller.selectItem("x")
        a.engine.becomeReady()
        val parked = a.engine
        a.controller.selectItem("y")
        assertEquals(2, a.controller.nativePlayerCount)

        b.load("z")

        assertTrue(parked.isReleased, "the kept item made room")
        assertEquals(1, a.controller.nativePlayerCount)
        assertEquals(PlayerLifecycle.Active, a.controller.state.value.lifecycle)
        assertEquals(1, b.controller.nativePlayerCount)
    }

    @Test
    fun capSuspendsTheLeastRecentlyUsedIdlePlayer() = coordinatorTest(CoordinatorConfig(maxActivePlayers = 2)) {
        val a = it.player(Idle)
        val b = it.player(Idle)
        val c = it.player(Idle)
        a.load("x")
        b.load("y")

        c.load("z")

        assertEquals(PlayerLifecycle.Suspended, a.controller.state.value.lifecycle)
        assertTrue(a.engine.isReleased)
        assertEquals(PlayerLifecycle.Active, b.controller.state.value.lifecycle)
        assertEquals(1, c.controller.nativePlayerCount)
        assertTrue(it.warnings.isEmpty())
    }

    @Test
    fun capNeverSuspendsPlayingPlayers() = coordinatorTest(CoordinatorConfig(maxActivePlayers = 2)) {
        val a = it.player(Idle)
        val b = it.player(Idle)
        val c = it.player(Idle)
        a.load("x")
        b.load("y")
        a.controller.play()
        // b is now the least recently used, but a is playing.
        b.controller.selectItem("y")

        c.load("z")

        assertEquals(PlayerLifecycle.Active, a.controller.state.value.lifecycle)
        assertTrue(a.controller.state.value.isPlaying)
        assertEquals(PlayerLifecycle.Suspended, b.controller.state.value.lifecycle)
    }

    @Test
    fun capIsExceededWithAWarningWhenEveryOtherPlayerPlays() = coordinatorTest(CoordinatorConfig(maxActivePlayers = 1)) {
        val a = it.player()
        val b = it.player()
        a.load("x")

        b.load("y")

        assertTrue(a.controller.state.value.isPlaying)
        assertEquals(1, b.controller.nativePlayerCount)
        assertEquals(1, it.warnings.size, it.warnings.toString())
    }

    @Test
    fun aSuspendedPlayerComesBackWhenPlayed() = coordinatorTest(CoordinatorConfig(maxActivePlayers = 1)) {
        val a = it.player(Idle)
        val b = it.player(Idle)
        a.load("x")
        b.load("y")
        assertEquals(PlayerLifecycle.Suspended, a.controller.state.value.lifecycle)

        a.controller.play()

        assertEquals(PlayerLifecycle.Active, a.controller.state.value.lifecycle)
        assertEquals(PlayerLifecycle.Suspended, b.controller.state.value.lifecycle, "b made room in turn")
    }

    // endregion

    // region Owned controllers

    @Test
    fun ownedControllersAreSharedByKey() = coordinatorTest {
        val first = it.coordinator.controllerFor("feed-1")

        assertSame(first, it.coordinator.controllerFor("feed-1"))
        assertNotSame(first, it.coordinator.controllerFor("feed-2"))
    }

    @Test
    fun ownedControllerIsReleasedAfterRetentionWithoutSurface() = coordinatorTest {
        val first = it.coordinator.controllerFor("feed-1") as DefaultPlayerController
        val surface = Any()
        first.attachSurface(surface)
        first.detachSurface(surface)

        advanceTimeBy(1.5.seconds)
        assertEquals(PlayerLifecycle.Suspended, first.state.value.lifecycle)
        advanceTimeBy(30.seconds)
        runCurrent()

        assertTrue(first.isReleased)
        assertFalse(first in it.coordinator.players.value)
        assertNotSame(first, it.coordinator.controllerFor("feed-1"), "a released controller is replaced")
    }

    @Test
    fun ownedControllerThatNeverGetsASurfaceIsReleasedToo() = coordinatorTest {
        val controller = it.coordinator.controllerFor("feed-1") as DefaultPlayerController

        advanceTimeBy(32.seconds)
        runCurrent()

        assertTrue(controller.isReleased)
    }

    @Test
    fun ownedControllerWithASurfaceStaysActive() = coordinatorTest {
        val controller = it.coordinator.controllerFor("feed-1") as DefaultPlayerController
        controller.attachSurface(Any())

        advanceTimeBy(60.seconds)

        assertEquals(PlayerLifecycle.Active, controller.state.value.lifecycle)
    }

    @Test
    fun appControllersKeepTheirInstanceAfterRetention() = coordinatorTest {
        val a = it.player()
        val surface = Any()
        a.controller.attachSurface(surface)
        a.controller.detachSurface(surface)

        advanceTimeBy(60.seconds)

        assertEquals(PlayerLifecycle.Suspended, a.controller.state.value.lifecycle)
        assertTrue(a.controller in it.coordinator.players.value)
    }

    // endregion

    // region Audio session

    @Test
    fun audioSessionIsAmbientUnlessAnUnmutedPlayerPlays() = coordinatorTest {
        val a = it.player()
        val b = it.player(PlayerConfiguration(playback = PlaybackConfig(initialMuted = true)))
        assertEquals(listOf(false), it.audible, "ambient from the start")

        b.load("y")
        assertEquals(listOf(false), it.audible, "a muted player stays ambient")

        a.load("x")
        assertEquals(listOf(false, true), it.audible)

        a.engine.stall()
        a.engine.resumeFromStall()
        assertEquals(listOf(false, true), it.audible, "stalls don't flip the session")

        a.controller.setMuted(true)
        assertEquals(listOf(false, true, false), it.audible)

        a.controller.setMuted(false)
        a.controller.release()
        assertEquals(listOf(false, true, false, true, false), it.audible)
    }

    @Test
    fun audioSessionIsLeftAloneWhenNotManaged() = coordinatorTest(CoordinatorConfig(manageAudioSession = false)) {
        it.player().load("x")

        assertTrue(it.audible.isEmpty())
    }

    // endregion

    private fun coordinatorTest(
        config: CoordinatorConfig = CoordinatorConfig(),
        body: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val harness = Harness(this, config)
        try {
            body(harness)
        } finally {
            harness.coordinator.releaseAll()
        }
    }

    private class TestPlayer(val controller: DefaultPlayerController, private val engines: List<FakePlaybackEngine>) {
        val engine: FakePlaybackEngine get() = engines.last()

        fun load(itemId: String) {
            controller.setItems(listOf(item(itemId)))
            controller.selectItem(itemId)
            engine.becomeReady()
        }
    }

    private class Harness(private val scope: TestScope, config: CoordinatorConfig) {
        val warnings = mutableListOf<String>()
        val audible = mutableListOf<Boolean>()
        val events = mutableListOf<PlayerEvent>()

        val coordinator = PlayerCoordinator(
            config = config,
            audioSession = { audible += it },
            log = { warnings += it },
            createOwned = { coordinator, key, configuration, refresher ->
                controller(coordinator, configuration, mutableListOf(), ownerKey = key, refresher = refresher)
            },
        )

        init {
            scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                coordinator.events.collect { events += it }
            }
        }

        fun player(configuration: PlayerConfiguration = PlayerConfiguration()): TestPlayer {
            val engines = mutableListOf<FakePlaybackEngine>()
            return TestPlayer(controller(coordinator, configuration, engines), engines)
        }

        fun eventsOf(player: TestPlayer): List<PlayerEventType> =
            events.filter { it.playerId == player.controller.id }.map { it.type }

        private fun controller(
            coordinator: PlayerCoordinator,
            configuration: PlayerConfiguration,
            engines: MutableList<FakePlaybackEngine>,
            ownerKey: String? = null,
            refresher: co.liebi.videoplayer.core.SourceRefresher? = null,
        ) = DefaultPlayerController(
            configuration = configuration,
            sourceRefresher = refresher,
            engineFactory = { FakePlaybackEngine().also { engines += it } },
            coordinator = coordinator,
            ownerKey = ownerKey,
            dispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = FixedClock,
            timeSource = scope.testScheduler.timeSource,
            log = { warnings += it },
        )
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(0)
    }

    private companion object {
        /** Loads paused and keeps no prepared items, so each player holds exactly one native player. */
        val Idle = PlayerConfiguration(
            playback = PlaybackConfig(playOnItemSelected = false),
            lifecycle = LifecycleConfig(keepPreparedItems = 0),
        )

        fun item(id: String) = MediaItem(id, MediaSource.Url("https://example.com/$id.m3u8"))
    }
}
