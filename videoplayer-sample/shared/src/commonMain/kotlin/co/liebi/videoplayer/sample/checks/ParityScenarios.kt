package co.liebi.videoplayer.sample.checks

import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.PlaybackConfig
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerConfiguration
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerEventType
import co.liebi.videoplayer.core.PlayerLifecycle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// Declared before the scenarios, which use them while being initialized.
internal val Muted = PlayerConfiguration(playback = PlaybackConfig(initialMuted = true))

/** Counts as playing with sound (unmuted, volume above 0) for audio focus and the audio session, but can't be heard. */
internal val Inaudible = PlayerConfiguration(playback = PlaybackConfig(initialVolume = 0.001f))

internal val Hls = MediaItem("hls", MediaSource.Url("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"))
internal val Mp4 = MediaItem(
    "mp4",
    MediaSource.Url("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_2MB.mp4"),
)
internal val Missing = MediaItem("missing", MediaSource.Url("https://test-streams.mux.dev/does-not-exist.m3u8"))

/**
 * The parity suite (§17): scripted scenarios on the real engines. Both platforms must emit exactly the
 * [ParityScenario.expected] sequence, so passing on both means Android and iOS emit identical sequences (§18).
 * Events are compared as [token]s, which leave out timing and network-dependent stalls, in [canonicalOrder].
 */
internal val ParityScenarios = listOf(
    ParityScenario(
        name = "play-pause",
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)", "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Hls)
        awaitPosition(2.seconds)
        controller.pause()
        awaitState("paused") { !it.playWhenReady }
    },
    ParityScenario(
        name = "pause-before-ready",
        expected = listOf("ItemChanged(null)", "FirstFrameRendered", "PlaybackPaused(User)", "PlayerReleased"),
    ) {
        load(Hls)
        controller.pause()
        awaitState("first frame while paused") { it.isFirstFrameRendered && it.status == PlaybackStatus.Ready }
        pause(2.seconds)
        check(!controller.state.value.isPlaying, "playback started on its own after pause()")
    },
    ParityScenario(
        name = "seek",
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "SeekStarted(to=30s)", "SeekCompleted(30s)", "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Hls)
        awaitPosition(2.seconds)
        controller.seekTo(30.seconds)
        awaitEvent("seek completed") { it is PlayerEventType.SeekCompleted }
        awaitPosition(31.seconds)
        check(controller.state.value.playWhenReady, "seeking changed play intent")
        controller.pause()
    },
    ParityScenario(
        name = "switch-and-back",
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "ItemChanged(hls)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "ItemChanged(mp4)", "FirstFrameRendered", "PlaybackStarted(first=false)",
            "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        controller.setItems(listOf(Hls, Mp4))
        controller.selectItem(Hls.id)
        awaitPosition(3.seconds)
        val left = controller.progress.value.position
        controller.selectItem(Mp4.id)
        awaitState("mp4 playing") { it.currentItemId == Mp4.id && it.isPlaying }
        awaitPosition(1.seconds)
        controller.selectItem(Hls.id)
        awaitState("hls playing again") { it.currentItemId == Hls.id && it.isPlaying }
        // Acceptance (§18): switching A, B, A resumes A within 0.5 s of where it stopped.
        val resumed = controller.progress.value.position
        check((resumed - left).absoluteValue <= 0.5.seconds, "resumed at $resumed, left at $left")
        // Even when paused at once, the kept item must show a frame rather than the poster.
        controller.pause()
        awaitState("a frame of the kept item while paused", 5.seconds) { it.isFirstFrameRendered }
    },
    ParityScenario(
        name = "end-without-replay",
        configuration = Muted.copy(playback = Muted.playback.copy(autoReplay = false)),
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "PlaybackCompleted", "PlaybackPaused(Ended)",
            "SeekStarted(to=0s)", "SeekCompleted(0s)", "PlaybackStarted(first=false)",
            "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Mp4)
        awaitState("ended", 40.seconds) { it.status == PlaybackStatus.Ended }
        controller.play()
        awaitState("replaying") { it.isPlaying }
        awaitPosition(1.seconds)
        controller.pause()
    },
    ParityScenario(
        name = "auto-replay",
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "PlaybackCompleted", "SeekStarted(to=0s)", "SeekCompleted(0s)",
            "PlaybackCompleted", "SeekStarted(to=0s)", "SeekCompleted(0s)",
            "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Mp4)
        awaitEvents("two loops", 60.seconds) { events -> events.count { it is PlayerEventType.SeekCompleted } >= 2 }
        controller.pause()
    },
    ParityScenario(
        name = "not-found",
        expected = listOf("ItemChanged(null)", "PlaybackError(NotFound, final=true)", "PlayerReleased"),
    ) {
        load(Missing)
        awaitState("error") { it.status == PlaybackStatus.Error }
    },
    ParityScenario(
        name = "suspend-resume",
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "PlaybackPaused(Suspended)", "PlayerSuspended",
            "PlayerResumed", "FirstFrameRendered", "PlaybackStarted(first=false)",
            "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Hls)
        awaitPosition(3.seconds)
        controller.suspend()
        awaitState("suspended") { it.lifecycle == PlayerLifecycle.Suspended }
        val left = controller.progress.value.position
        controller.play()
        awaitState("playing after resume") { it.isPlaying }
        val resumed = controller.progress.value.position
        check((resumed - left).absoluteValue <= 0.5.seconds, "resumed at $resumed, suspended at $left")
        controller.pause()
    },
    ParityScenario(
        name = "hold",
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "PlaybackPaused(Hold)", "PlaybackStarted(first=false)", "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Hls)
        awaitPosition(2.seconds)
        controller.beginHold()
        pause(1.seconds)
        controller.endHold()
        awaitState("playing after hold") { it.isPlaying }
        controller.pause()
    },
    ParityScenario(
        name = "two-coordinators-audible",
        configuration = Inaudible,
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)", "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Hls)
        awaitPosition(2.seconds)
        // Audio focus and the audio session are app-wide: a player of another coordinator playing with sound
        // must not pause this one.
        val other = PlayerController(Inaudible, coordinator = PlayerCoordinator())
        try {
            other.setItems(listOf(Mp4))
            other.selectItem(Mp4.id)
            withTimeoutOrNull(30.seconds) { other.state.first { it.isPlaying } }
                ?: throw ScenarioFailure("the other coordinator's player never started")
            pause(2.seconds)
            check(controller.state.value.isPlaying, "paused by the other coordinator: ${controller.state.value.pauseReason}")
            check(other.state.value.isPlaying, "the other coordinator's player stopped: ${other.state.value.pauseReason}")
        } finally {
            other.release()
        }
        controller.pause()
    },
    ParityScenario(
        name = "fullscreen",
        configuration = Muted.copy(fullscreenEnabled = true),
        expected = listOf(
            "ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
            "PresentationChanged(Inline->Fullscreen)", "PresentationChanged(Fullscreen->Inline)",
            "PlaybackPaused(User)", "PlayerReleased",
        ),
    ) {
        load(Hls)
        awaitPosition(2.seconds)
        val playback = measurePlayback {
            controller.enterFullscreen()
            pause(1500.milliseconds)
            controller.exitFullscreen()
            pause(1500.milliseconds)
        }
        // Acceptance (§18): no re-prepare and no position jump over 0.5 s.
        check(playback.drift <= 0.5.seconds, "position drifted ${playback.drift} from the wall clock while switching")
        check(!playback.reprepared, "the item was prepared again")
        controller.pause()
    },
)

/** One event as compared across platforms: no timestamps, positions rounded, stalls left out. */
internal fun PlayerEventType.token(): String? = when (this) {
    is PlayerEventType.BufferingStarted, is PlayerEventType.BufferingEnded -> null
    is PlayerEventType.FirstFrameRendered -> "FirstFrameRendered"
    is PlayerEventType.PlaybackStarted -> "PlaybackStarted(first=$isFirstStart)"
    is PlayerEventType.PlaybackPaused -> "PlaybackPaused($reason)"
    is PlayerEventType.SeekStarted -> "SeekStarted(to=${to.inWholeMilliseconds.roundToSeconds()}s)"
    is PlayerEventType.SeekCompleted -> "SeekCompleted(${position.inWholeMilliseconds.roundToSeconds()}s)"
    is PlayerEventType.ItemChanged -> "ItemChanged($previousItemId)"
    is PlayerEventType.PlaybackError -> "PlaybackError(${error.category}, final=$isFinal)"
    is PlayerEventType.RetryScheduled -> "RetryScheduled($attempt)"
    is PlayerEventType.CredentialsRefreshed -> "CredentialsRefreshed($trigger)"
    is PlayerEventType.PresentationChanged -> "PresentationChanged($from->$to)"
    else -> toString()
}

private fun Long.roundToSeconds(): Long = (this + 500) / 1000

/**
 * Moves each FirstFrameRendered right after the load it belongs to (ItemChanged or PlayerResumed). When the first
 * frame is decoded relative to the minimum buffer depends on the decoder and the network, so it lands before or
 * after PlaybackStarted even on one platform. It must still appear exactly once per load, and never in another one.
 */
internal fun canonicalOrder(tokens: List<String>): List<String> {
    val result = mutableListOf<String>()
    var loadEnd = -1
    for (token in tokens) {
        when {
            token == "FirstFrameRendered" && loadEnd >= 0 -> result.add(++loadEnd, token)
            token.startsWith("ItemChanged") || token == "PlayerResumed" -> {
                result += token
                loadEnd = result.lastIndex
            }
            else -> result += token
        }
    }
    return result
}
