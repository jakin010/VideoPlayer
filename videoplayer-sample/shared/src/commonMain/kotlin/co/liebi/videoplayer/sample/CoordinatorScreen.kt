package co.liebi.videoplayer.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import co.liebi.videoplayer.core.CoordinatorConfig
import co.liebi.videoplayer.core.LifecycleConfig
import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.PlaybackConfig
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerLifecycle
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.rememberPlayerController
import co.liebi.videoplayer.ui.VideoPlayer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

private val MaxPlayerOptions = listOf(1, 2, 3, 4, 6)

/** Feed players start muted and paused; the user or autoplay starts them. */
private val FeedConfiguration = PlayerConfiguration(
    playback = PlaybackConfig(initialMuted = true, playOnItemSelected = false),
    lifecycle = LifecycleConfig(keepPreparedItems = 0),
)

/** Owns the coordinator. Changing its config replaces it, releasing every player of the old one. */
class CoordinatorViewModel : ViewModel() {
    var config by mutableStateOf(CoordinatorConfig())
        private set
    var coordinator by mutableStateOf(PlayerCoordinator(config))
        private set

    fun updateConfig(config: CoordinatorConfig) {
        if (config == this.config) return
        coordinator.releaseAll()
        this.config = config
        coordinator = PlayerCoordinator(config)
    }

    override fun onCleared() {
        coordinator.releaseAll()
    }
}

@Composable
fun CoordinatorScreen(viewModel: CoordinatorViewModel) {
    val coordinator = viewModel.coordinator
    var autoplay by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val mostVisible by remember(listState) { derivedStateOf { listState.mostVisibleItemKey() } }

    Column(Modifier.fillMaxSize()) {
        CoordinatorSettings(
            config = viewModel.config,
            onConfigChange = viewModel::updateConfig,
            autoplay = autoplay,
            onAutoplayChange = { autoplay = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerCounts(coordinator, Modifier.weight(1f))
            TextButton(onClick = coordinator::releaseAll) { Text("Release all") }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(FeedItems, key = { it.id }) { item ->
                FeedVideo(
                    item = item,
                    coordinator = coordinator,
                    autoplay = autoplay,
                    isMostVisible = item.id == mostVisible,
                )
            }
        }
        HorizontalDivider()
        CoordinatorEventLog(coordinator, Modifier.height(112.dp).fillMaxWidth().padding(horizontal = 16.dp))
    }
}

@Composable
private fun CoordinatorSettings(
    config: CoordinatorConfig,
    onConfigChange: (CoordinatorConfig) -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledSwitch("Single active", config.singleActivePlayer) {
                onConfigChange(config.copy(singleActivePlayer = it))
            }
            LabeledSwitch("Autoplay", autoplay, onAutoplayChange)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Max players", fontSize = 14.sp)
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                MaxPlayerOptions.forEachIndexed { index, max ->
                    SegmentedButton(
                        modifier = Modifier.height(36.dp),
                        selected = config.maxActivePlayers == max,
                        onClick = { onConfigChange(config.copy(maxActivePlayers = max)) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = MaxPlayerOptions.size),
                        icon = {},
                        label = { Text("$max", fontSize = 12.sp) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, fontSize = 14.sp)
    }
}

/** Lifecycles across every registered player, so the cap and retention can be watched. */
@Composable
private fun PlayerCounts(coordinator: PlayerCoordinator, modifier: Modifier = Modifier) {
    val players by coordinator.players.collectAsState()
    val states by remember(players) {
        if (players.isEmpty()) flowOf(emptyList()) else combine(players.map { it.state }) { it.toList() }
    }.collectAsState(players.map { it.state.value })
    val text = buildString {
        append("${players.size} players: ${states.count { it.lifecycle == PlayerLifecycle.Active }} active")
        append(", ${states.count { it.lifecycle == PlayerLifecycle.Suspended }} suspended")
        append(", ${states.count { it.isPlaying }} playing")
    }
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun FeedVideo(item: MediaItem, coordinator: PlayerCoordinator, autoplay: Boolean, isMostVisible: Boolean) {
    // Owned by the coordinator: it outlives this list item and is released after the retention period.
    val controller = rememberPlayerController(key = item.id, coordinator = coordinator, configuration = FeedConfiguration)
    LaunchedEffect(controller) {
        controller.setItems(listOf(item))
        controller.selectItem(item.id)
    }
    LaunchedEffect(controller, autoplay, isMostVisible) {
        if (!autoplay) return@LaunchedEffect
        if (isMostVisible) controller.play() else controller.pause()
    }
    val state by controller.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            item.title.orEmpty(),
            modifier = Modifier.padding(horizontal = 16.dp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        VideoPlayer(
            controller = controller,
            modifier = Modifier.background(Color.Black),
            poster = { Poster(item.title) },
        )
        Text(
            state.summary(controller.id),
            modifier = Modifier.padding(horizontal = 16.dp),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun PlayerState.summary(playerId: String): String = buildString {
    append("$playerId · $lifecycle · $status")
    if (!playWhenReady && pauseReason != null) append(" · paused: $pauseReason")
    if (isPlaying) append(" · playing")
    error?.let { append(" · error: ${it.category}") }
}

@Composable
private fun CoordinatorEventLog(coordinator: PlayerCoordinator, modifier: Modifier) {
    val events = remember(coordinator) { mutableStateListOf<PlayerEvent>() }
    LaunchedEffect(coordinator) {
        coordinator.events.collect { event ->
            events.add(0, event)
            if (events.size > 60) events.removeAt(events.lastIndex)
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        events.forEach { event ->
            Text(
                text = "${event.playerId} ${event.itemId ?: "-"}  ${event.type}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The key of the item with the largest visible share of its height; the topmost wins ties. */
private fun LazyListState.mostVisibleItemKey(): Any? {
    val info = layoutInfo
    return info.visibleItemsInfo.maxByOrNull { item ->
        val top = maxOf(item.offset, info.viewportStartOffset)
        val bottom = minOf(item.offset + item.size, info.viewportEndOffset)
        (bottom - top).coerceAtLeast(0).toFloat() / item.size.coerceAtLeast(1)
    }?.key
}
