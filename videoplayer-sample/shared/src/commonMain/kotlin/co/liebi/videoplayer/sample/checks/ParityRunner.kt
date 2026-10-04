package co.liebi.videoplayer.sample.checks

import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerEventType
import co.liebi.videoplayer.core.PlayerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal class ParityScenario(
    val name: String,
    val expected: List<String>,
    val configuration: PlayerConfiguration = Muted,
    val script: suspend ScenarioScope.() -> Unit,
)

internal data class ScenarioResult(
    val name: String,
    val expected: List<String>,
    /** The events in [canonicalOrder]; [raw] is the order they arrived in. */
    val actual: List<String>,
    val raw: List<String>,
    val problems: List<String>,
) {
    val passed: Boolean get() = actual == expected && problems.isEmpty()
}

internal class ScenarioFailure(message: String) : Exception(message)

internal class ScenarioScope(
    val controller: PlayerController,
    private val events: MutableStateFlow<List<PlayerEvent>>,
    private val problems: MutableList<String>,
) {
    fun load(item: MediaItem) {
        controller.setItems(listOf(item))
        controller.selectItem(item.id)
    }

    fun check(condition: Boolean, problem: String) {
        if (!condition) problems += problem
    }

    suspend fun pause(duration: Duration) = delay(duration)

    suspend fun awaitState(what: String, timeout: Duration = 30.seconds, predicate: (PlayerState) -> Boolean) {
        withTimeoutOrNull(timeout) { controller.state.first(predicate) }
            ?: throw ScenarioFailure("timed out waiting for $what (${controller.state.value.summary()})")
    }

    suspend fun awaitPosition(atLeast: Duration, timeout: Duration = 30.seconds) {
        withTimeoutOrNull(timeout) { controller.progress.first { it.position >= atLeast } }
            ?: throw ScenarioFailure("timed out waiting for position $atLeast (${controller.state.value.summary()})")
    }

    suspend fun awaitEvent(what: String, timeout: Duration = 30.seconds, predicate: (PlayerEventType) -> Boolean) =
        awaitEvents(what, timeout) { types -> types.any(predicate) }

    suspend fun awaitEvents(what: String, timeout: Duration = 30.seconds, predicate: (List<PlayerEventType>) -> Boolean) {
        withTimeoutOrNull(timeout) { events.first { list -> predicate(list.map { it.type }) } }
            ?: throw ScenarioFailure("timed out waiting for $what")
    }

    /** Runs [block] while playing and reports how far the position strayed from the wall clock. */
    suspend fun measurePlayback(block: suspend () -> Unit): PlaybackMeasurement = coroutineScope {
        var reprepared = false
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.state.collect { if (it.status == PlaybackStatus.Preparing) reprepared = true }
        }
        val startPosition = controller.progress.value.position
        val mark = TimeSource.Monotonic.markNow()
        block()
        // The position is published about every 250 ms, so it can lag by that much.
        val drift = (controller.progress.value.position - startPosition - mark.elapsedNow()).absoluteValue
        watcher.cancel()
        PlaybackMeasurement((drift - ProgressInterval).coerceAtLeast(Duration.ZERO), reprepared)
    }
}

internal data class PlaybackMeasurement(val drift: Duration, val reprepared: Boolean)

/**
 * Runs [scenario] on a fresh muted player of [coordinator]. [show] puts the player on screen so the
 * platform renders frames, and takes it away again at the end.
 */
internal suspend fun runScenario(
    scenario: ParityScenario,
    coordinator: PlayerCoordinator,
    show: (PlayerController?) -> Unit,
): ScenarioResult = coroutineScope {
    val controller = PlayerController(scenario.configuration, coordinator = coordinator)
    val events = MutableStateFlow<List<PlayerEvent>>(emptyList())
    val problems = mutableListOf<String>()
    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
        controller.events.collect { event -> events.update { it + event } }
    }
    show(controller)
    try {
        withTimeout(ScenarioTimeout) { ScenarioScope(controller, events, problems).(scenario.script)() }
    } catch (e: ScenarioFailure) {
        problems += e.message.orEmpty()
    } catch (e: CancellationException) {
        if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
        problems += "scenario took longer than $ScenarioTimeout"
    }
    controller.release()
    // Anything arriving after PlayerReleased is a bug (§15).
    delay(500.milliseconds)
    show(null)
    collector.cancel()

    val recorded = events.value
    val released = recorded.indexOfFirst { it.type == PlayerEventType.PlayerReleased }
    if (released >= 0 && released != recorded.lastIndex) problems += "events after PlayerReleased"
    recorded.filter { it.playerId != controller.id }.forEach { problems += "wrong playerId on ${it.type}" }
    recorded.dropWhile { it.type !is PlayerEventType.ItemChanged }
        .filter { it.itemId == null }
        .forEach { problems += "no itemId on ${it.type}" }

    val raw = recorded.mapNotNull { it.type.token() }
    ScenarioResult(scenario.name, scenario.expected, canonicalOrder(raw), raw, problems)
}

private fun PlayerState.summary() = "status=$status playWhenReady=$playWhenReady lifecycle=$lifecycle error=${error?.category}"

private val ScenarioTimeout = 120.seconds
private val ProgressInterval = 250.milliseconds
