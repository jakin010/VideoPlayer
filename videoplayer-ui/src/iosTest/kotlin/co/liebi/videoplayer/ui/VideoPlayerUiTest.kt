package co.liebi.videoplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.test.FakePlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Drives `VideoPlayer` with real touches. While playing, the scrubber animates every frame, so the
 * frame clock is advanced by hand.
 */
@OptIn(ExperimentalTestApi::class)
class VideoPlayerUiTest {

    private fun playingController(position: Duration = 50.seconds) = FakePlayerController(
        initialState = PlayerState(
            status = PlaybackStatus.Ready,
            playWhenReady = true,
            duration = 100.seconds,
            seekableRange = Duration.ZERO..100.seconds,
            isSeekable = true,
        ),
        initialProgress = PlaybackProgress(position = position),
    )

    private val FakePlayerController.seeks: List<Duration>
        get() = calls.filterIsInstance<FakePlayerController.Call.SeekTo>().map { it.position }

    private fun ComposeUiTest.showPlayer(controller: FakePlayerController) {
        mainClock.autoAdvance = false
        setContent { Box(Modifier.width(320.dp)) { VideoPlayer(controller, Modifier.testTag("player")) } }
        mainClock.advanceTimeByFrame()
    }

    private fun ComposeUiTest.controlsShown(): Boolean =
        onAllNodesWithContentDescriptionCount("Pause") + onAllNodesWithContentDescriptionCount("Play") > 0

    private fun ComposeUiTest.onAllNodesWithContentDescriptionCount(label: String): Int =
        onAllNodes(androidx.compose.ui.test.hasContentDescription(label)).fetchSemanticsNodes().size

    @Test
    fun controlsHideAfterThreeSecondsWhilePlaying() = runComposeUiTest {
        showPlayer(playingController())
        assertTrue(controlsShown())

        mainClock.advanceTimeBy(2_500)
        assertTrue(controlsShown(), "still visible before the timeout")

        mainClock.advanceTimeBy(1_000)
        assertFalse(controlsShown())
    }

    @Test
    fun controlsStayVisibleWhilePaused() = runComposeUiTest {
        val controller = playingController()
        showPlayer(controller)
        controller.pause()
        mainClock.advanceTimeBy(10_000)

        assertTrue(controlsShown())
    }

    @Test
    fun singleTapTogglesControlsAfterTheDoubleTapTimeout() = runComposeUiTest {
        val controller = playingController()
        showPlayer(controller)
        onNodeWithTag("player").performTouchInput { click(center) }

        mainClock.advanceTimeBy(100)
        assertTrue(controlsShown(), "a first tap may still become a double tap")

        mainClock.advanceTimeBy(800)
        assertFalse(controlsShown())

        onNodeWithTag("player").performTouchInput { click(center) }
        mainClock.advanceTimeBy(800)
        assertTrue(controlsShown())
        assertTrue(controller.calls.isEmpty(), "taps on the video never press a control: ${controller.calls}")
    }

    @Test
    fun doubleTapSeeksAndFurtherTapsAddSteps() = runComposeUiTest {
        val controller = playingController(position = 50.seconds)
        showPlayer(controller)

        onNodeWithTag("player").performTouchInput {
            val right = Offset(width * 0.85f, height * 0.3f)
            click(right)
            advanceEventTime(100)
            click(right)
            advanceEventTime(100)
            click(right)
        }
        mainClock.advanceTimeByFrame()

        assertEquals(listOf(60.seconds, 70.seconds), controller.seeks)
        onNodeWithText("+20 s").assertExists()
        assertTrue(controller.state.value.playWhenReady, "seeking never changes play intent")

        onNodeWithTag("player").performTouchInput {
            val left = Offset(width * 0.15f, height * 0.3f)
            advanceEventTime(1_000)
            click(left)
            advanceEventTime(100)
            click(left)
        }
        assertEquals(60.seconds, controller.seeks.last(), "back one step from the current position")
    }

    @Test
    fun doubleTapInTheMiddleDoesNothing() = runComposeUiTest {
        val controller = playingController()
        showPlayer(controller)

        onNodeWithTag("player").performTouchInput {
            click(Offset(centerX, height * 0.3f))
            advanceEventTime(100)
            click(Offset(centerX, height * 0.3f))
        }
        mainClock.advanceTimeBy(1_000)

        assertTrue(controller.seeks.isEmpty())
    }

    @Test
    fun holdPausesAndReleaseResumesWithoutShowingControls() = runComposeUiTest {
        val controller = playingController()
        showPlayer(controller)
        mainClock.advanceTimeBy(3_500)
        assertFalse(controlsShown())

        onNodeWithTag("player").performTouchInput { down(Offset(centerX, height * 0.3f)) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(PauseReason.Hold, controller.state.value.pauseReason)
        assertFalse(controlsShown(), "a hold keeps the frame unobstructed")

        onNodeWithTag("player").performTouchInput { up() }
        mainClock.advanceTimeByFrame()
        assertTrue(controller.state.value.playWhenReady)
        assertEquals(
            listOf<FakePlayerController.Call>(FakePlayerController.Call.BeginHold, FakePlayerController.Call.EndHold),
            controller.calls,
        )
    }

    @Test
    fun tappingAControlDoesNotToggleTheControls() = runComposeUiTest {
        val controller = playingController()
        showPlayer(controller)

        onNodeWithContentDescription("Mute").performClick()
        mainClock.advanceTimeBy(800)

        assertTrue(controller.state.value.isMuted)
        assertTrue(controlsShown())
    }
}
