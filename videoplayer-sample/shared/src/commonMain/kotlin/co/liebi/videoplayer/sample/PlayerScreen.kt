package co.liebi.videoplayer.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import co.liebi.videoplayer.core.PlaybackConfig
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerEvent
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.VideoAspectRatio
import co.liebi.videoplayer.core.VideoTransform
import co.liebi.videoplayer.ui.VideoPlayer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val AspectRatios = listOf(
    "16:9" to VideoAspectRatio.Ratio16x9,
    "4:3" to VideoAspectRatio.Ratio4x3,
    "1:1" to VideoAspectRatio.Ratio1x1,
    "9:16" to VideoAspectRatio.Ratio9x16,
    "Native" to VideoAspectRatio.Native,
)

private val Speeds = listOf(1f, 1.5f, 2f, 0.5f)

/** App-owned controller: it survives Android configuration changes and is released with the ViewModel. */
class PlayerViewModel : ViewModel() {
    val controller: PlayerController = PlayerController(
        configuration = PlayerConfiguration(playback = PlaybackConfig(initialMuted = true), fullscreenEnabled = true),
        items = SampleItems,
    )

    override fun onCleared() {
        controller.release()
    }
}

@Composable
fun PlayerScreen(controller: PlayerController, theme: Int, onThemeChange: (Int) -> Unit) {
    val state by controller.state.collectAsState()
    val currentItem = SampleItems.firstOrNull { it.id == state.currentItemId }
    var aspectRatio by remember { mutableStateOf(AspectRatios.first().second) }
    var transform by remember { mutableStateOf<VideoTransform?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VideoPlayer(
            controller = controller,
            modifier = Modifier.background(Color.Black),
            aspectRatio = aspectRatio,
            poster = { Poster(currentItem?.title) },
            transform = transform,
        )

        AspectRatioSwitch(
            selected = aspectRatio,
            onSelect = { aspectRatio = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )

        TransformControls(transform, onChange = { transform = it }, Modifier.padding(horizontal = 16.dp))

        ThemeSwitch(theme, onThemeChange, Modifier.fillMaxWidth().padding(horizontal = 16.dp))

        DebugControls(controller, state, Modifier.padding(horizontal = 16.dp))

        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SampleItems.forEach { item ->
                FilterChip(
                    selected = item.id == state.currentItemId,
                    onClick = { controller.selectItem(item.id) },
                    label = { Text(item.title.orEmpty()) },
                )
            }
        }

        StateSummary(state, Modifier.padding(horizontal = 16.dp))
        HorizontalDivider()
        EventLog(controller, Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun AspectRatioSwitch(
    selected: VideoAspectRatio,
    onSelect: (VideoAspectRatio) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        AspectRatios.forEachIndexed { index, (label, ratio) ->
            SegmentedButton(
                selected = ratio == selected,
                onClick = { onSelect(ratio) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = AspectRatios.size),
                icon = {},
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

/** Switches the player theme while the video plays. */
@Composable
private fun ThemeSwitch(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        PlayerThemes.forEachIndexed { index, (label, _) ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = PlayerThemes.size),
                icon = {},
                label = { Text(label, fontSize = 12.sp) },
            )
        }
    }
}

/** Test-only commands that the default controls don't cover. */
@Composable
private fun DebugControls(controller: PlayerController, state: PlayerState, modifier: Modifier) {
    val progress by controller.progress.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${progress.position.format()} / ${(state.duration ?: Duration.ZERO).format()}  ·  buffered ${progress.bufferedPosition.format()}",
            fontSize = 12.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { controller.seekTo(controller.progress.value.position - 10.seconds) }) { Text("-10 s") }
            OutlinedButton(onClick = { controller.seekTo(controller.progress.value.position + 10.seconds) }) { Text("+10 s") }
            OutlinedButton(onClick = {
                val next = Speeds[(Speeds.indexOf(state.playbackSpeed) + 1) % Speeds.size]
                controller.setPlaybackSpeed(next)
            }) { Text("${state.playbackSpeed}x") }
            OutlinedButton(onClick = controller::suspend) { Text("Suspend") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = state.autoReplay, onCheckedChange = controller::setAutoReplay)
            Text("Auto replay")
        }
    }
}

@Composable
private fun StateSummary(state: PlayerState, modifier: Modifier) {
    val text = buildString {
        append("status=${state.status} playWhenReady=${state.playWhenReady} isPlaying=${state.isPlaying}")
        append("\npauseReason=${state.pauseReason} lifecycle=${state.lifecycle} seeking=${state.isSeeking}")
        append("\nvideoSize=${state.videoSize?.let { "${it.width}x${it.height}" }} firstFrame=${state.isFirstFrameRendered}")
        append(" muted=${state.isMuted} retry=${state.retryAttempt} error=${state.error?.category}")
    }
    Text(text, modifier = modifier, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
}

@Composable
private fun EventLog(controller: PlayerController, modifier: Modifier) {
    val events = remember(controller) { mutableStateListOf<PlayerEvent>() }
    LaunchedEffect(controller) {
        controller.events.collect { event ->
            events.add(0, event)
            if (events.size > 40) events.removeAt(events.lastIndex)
        }
    }
    Column(modifier) {
        events.forEach { event ->
            Text(
                text = "${event.position?.format() ?: "--:--"}  ${event.type}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
    }
}

