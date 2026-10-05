package co.liebi.videoplayer.sample.checks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.liebi.videoplayer.core.LifecycleConfig
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.VideoPlayerSurface
import co.liebi.videoplayer.core.rememberPlayerController
import co.liebi.videoplayer.sample.FeedItems
import co.liebi.videoplayer.sample.Poster
import co.liebi.videoplayer.ui.VideoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Runs the parity suite and the leak checks (§17) on the real engines and shows the results. */
class ChecksViewModel : ViewModel() {
    /** Players under test belong to their own coordinator, so the other tabs don't interfere. */
    val coordinator = PlayerCoordinator()

    internal var current by mutableStateOf<PlayerController?>(null)
        private set
    internal var feedRun by mutableStateOf<FeedRun?>(null)
        private set
    internal var status by mutableStateOf("Not run yet")
        private set
    internal val parityResults = mutableStateListOf<ScenarioResult>()
    internal val leakResults = mutableStateListOf<LeakResult>()
    var isRunning by mutableStateOf(false)
        private set
    private var autoRunStarted = false

    fun runParity() = start { parity() }

    fun runCycles() = start { cycles() }

    fun runFeed() = start { feed() }

    fun runAll() = start {
        parity()
        cycles()
        feed()
    }

    /** Starts the checks requested at launch, once. */
    fun autoRun(which: String) {
        if (autoRunStarted) return
        autoRunStarted = true
        when {
            // `parity:<name>,<name>` runs only those scenarios.
            which.startsWith("parity:") -> start { parity(which.removePrefix("parity:").split(',').toSet()) }
            which == "parity" -> runParity()
            which == "cycles" -> runCycles()
            which == "feed" -> runFeed()
            which == "leaks" -> start {
                cycles()
                feed()
            }
            else -> runAll()
        }
    }

    private fun start(block: suspend () -> Unit) {
        if (isRunning) return
        isRunning = true
        viewModelScope.launch {
            try {
                block()
                val passed = parityResults.count { it.passed } + leakResults.count { it.passed }
                val failed = parityResults.size + leakResults.size - passed
                status = "Done: $passed passed, $failed failed"
                report("DONE|passed=$passed|failed=$failed")
            } finally {
                isRunning = false
            }
        }
    }

    private suspend fun parity(only: Set<String>? = null) {
        parityResults.clear()
        val scenarios = ParityScenarios.filter { only == null || it.name in only }
        scenarios.forEachIndexed { index, scenario ->
            status = "Parity ${index + 1}/${scenarios.size}: ${scenario.name}"
            val result = runScenario(scenario, coordinator) { current = it }
            parityResults += result
            report(result.line())
        }
    }

    private suspend fun cycles() {
        leakResults.removeAll { it.name == "cycles" }
        val result = runCycles(coordinator, CycleCount, show = { current = it }) { status = "Cycle $it/$CycleCount" }
        leakResults += result
        report(result.line())
    }

    /** Scrolls a 100-item feed of coordinator-owned players, then releases them all and looks for leaks. */
    private suspend fun feed() {
        leakResults.removeAll { it.name == "feed" }
        val feedCoordinator = PlayerCoordinator()
        val released = mutableListOf<Released>()
        val natives = mutableListOf<WeakRef<Any>>()
        val seen = mutableSetOf<String>()
        val tracker = viewModelScope.launch {
            var previous = emptyList<PlayerController>()
            feedCoordinator.players.collect { players ->
                players.filter { seen.add(it.id) }.forEach { released += Released("feed ${it.id}", weakRef(it)) }
                val current = players.toSet()
                previous.filter { it !in current }.forEach { ChecksHooks.watchReleased(it, "feed player ${it.id} released") }
                previous = players
            }
        }
        val run = FeedRun(feedCoordinator)
        trackNativePlayers(natives) {
            feedRun = run
            val progress = viewModelScope.launch { run.position.collect { status = "Feed scroll $it/$FeedSize" } }
            run.scrolled.await()
            progress.cancel()
        }
        // Take the feed out of composition first, so nothing recreates the released players.
        feedRun = null
        delay(1.seconds)
        feedCoordinator.releaseAll()
        delay(500.milliseconds)
        tracker.cancel()
        val result = measureLeaks("feed", released, natives, feedCoordinator, emptyList())
        leakResults += result
        report(result.line())
    }

    override fun onCleared() {
        coordinator.releaseAll()
    }

    private companion object {
        const val CycleCount = 100
    }
}

internal class FeedRun(val coordinator: PlayerCoordinator) {
    val position = MutableStateFlow(0)
    val scrolled = CompletableDeferred<Unit>()
}

@Composable
fun ChecksScreen(viewModel: ChecksViewModel) {
    LaunchedEffect(viewModel) { ChecksHooks.autoRun?.let(viewModel::autoRun) }
    val running = viewModel.isRunning

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::runAll, enabled = !running) { Text("Run all") }
            OutlinedButton(onClick = viewModel::runParity, enabled = !running) { Text("Parity") }
            OutlinedButton(onClick = viewModel::runCycles, enabled = !running) { Text("100 cycles") }
            OutlinedButton(onClick = viewModel::runFeed, enabled = !running) { Text("Feed scroll") }
        }
        Text(viewModel.status, fontSize = 14.sp)
        Box(Modifier.fillMaxWidth().height(200.dp).background(Color.Black)) {
            val feedRun = viewModel.feedRun
            val current = viewModel.current
            when {
                feedRun != null -> FeedScroll(feedRun, Modifier.matchParentSize())
                // A fresh surface for each player, like a real screen showing a new video.
                current != null -> key(current) { VideoPlayerSurface(current, Modifier.matchParentSize()) }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(viewModel.parityResults, key = { "parity-${it.name}" }) { ResultRow(it) }
            items(viewModel.leakResults, key = { "leak-${it.name}" }) { LeakRow(it) }
        }
    }
}

@Composable
private fun ResultRow(result: ScenarioResult) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Verdict(result.passed, result.name)
        if (!result.passed) {
            Detail("expected: ${result.expected.joinToString()}")
            Detail("actual:   ${result.actual.joinToString()}")
            result.problems.forEach { Detail("problem:  $it") }
        }
    }
}

@Composable
private fun LeakRow(result: LeakResult) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Verdict(result.passed, "leaks: ${result.name}")
        Detail("${result.players} players, ${result.alive} still reachable after GC, ${result.registered} still registered")
        Detail("${result.nativePlayers} native players, ${result.nativeAlive} still alive after GC")
        result.problems.forEach { Detail("problem:  $it") }
        result.notes.forEach { Detail("note:     $it") }
    }
}

@Composable
private fun Verdict(passed: Boolean, name: String) {
    Text(
        text = "${if (passed) "PASS" else "FAIL"}  $name",
        fontWeight = FontWeight.Medium,
        color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Detail(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
}

/** Scrolls through [FeedSize] coordinator-owned players, playing the most visible one, like a real feed. */
@Composable
private fun FeedScroll(run: FeedRun, modifier: Modifier) {
    val listState = rememberLazyListState()
    val mostVisible by remember(listState) { derivedStateOf { listState.mostVisibleIndex() } }
    LazyColumn(modifier, state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(FeedSize, key = { it }) { index -> FeedCell(index, run.coordinator, isMostVisible = index == mostVisible) }
    }
    LaunchedEffect(run) {
        for (index in 0 until FeedSize) {
            listState.animateScrollToItem(index)
            run.position.value = index + 1
            delay(FeedStepDelay)
        }
        delay(1.seconds)
        run.scrolled.complete(Unit)
    }
}

@Composable
private fun FeedCell(index: Int, coordinator: PlayerCoordinator, isMostVisible: Boolean) {
    val item = FeedItems[index % FeedItems.size]
    val controller = rememberPlayerController(key = "feed-$index", coordinator = coordinator, configuration = FeedConfiguration)
    LaunchedEffect(controller) {
        controller.setItems(listOf(item))
        controller.selectItem(item.id)
    }
    LaunchedEffect(controller, isMostVisible) {
        if (isMostVisible) controller.play() else controller.pause()
    }
    VideoPlayer(controller, poster = { Poster("#$index ${item.title}") })
}

private fun LazyListState.mostVisibleIndex(): Int? {
    val info = layoutInfo
    return info.visibleItemsInfo.maxByOrNull { item ->
        val top = maxOf(item.offset, info.viewportStartOffset)
        val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
        (bottom - top).coerceAtLeast(0).toFloat() / item.size.coerceAtLeast(1)
    }?.index
}

private fun ScenarioResult.line(): String = buildString {
    append("parity|$name|${if (passed) "PASS" else "FAIL"}|${actual.joinToString(";")}|raw=${raw.joinToString(";")}")
    if (!passed) append("|expected=${expected.joinToString(";")}|problems=${problems.joinToString(";")}")
}

private fun LeakResult.line(): String =
    "leaks|$name|${if (passed) "PASS" else "FAIL"}|players=$players|alive=$alive|native=$nativePlayers|nativeAlive=$nativeAlive|registered=$registered|problems=${problems.joinToString(";")}|notes=${notes.joinToString(";")}"

private const val FeedSize = 100
private val FeedStepDelay = 700.milliseconds
private val FeedConfiguration = Muted.copy(
    playback = Muted.playback.copy(playOnItemSelected = false),
    lifecycle = LifecycleConfig(keepPreparedItems = 0),
)
