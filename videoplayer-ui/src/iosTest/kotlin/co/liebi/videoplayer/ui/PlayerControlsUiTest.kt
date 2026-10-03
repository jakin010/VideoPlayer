package co.liebi.videoplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.test.FakePlayerController
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class PlayerControlsUiTest {

    private fun seekableController(position: Duration = 50.seconds) = FakePlayerController(
        initialState = PlayerState(
            status = PlaybackStatus.Ready,
            duration = 100.seconds,
            seekableRange = Duration.ZERO..100.seconds,
            isSeekable = true,
        ),
        initialProgress = PlaybackProgress(position = position),
    )

    private val FakePlayerController.seeks: List<Duration>
        get() = calls.filterIsInstance<FakePlayerController.Call.SeekTo>().map { it.position }

    private fun assertNear(expected: Duration, actual: Duration) =
        assertTrue(abs((expected - actual).inWholeMilliseconds) <= 700, "expected about $expected but was $actual")

    @Test
    fun tapOnTheTrackNeverSeeks() = runComposeUiTest {
        val controller = seekableController()
        setContent { Box(Modifier.width(320.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) } }

        onNodeWithTag("scrubber").performTouchInput { click(Offset(width * 0.9f, centerY)) }

        assertTrue(controller.seeks.isEmpty())
    }

    @Test
    fun dragStartingOnTheHandleFollowsTheFinger() = runComposeUiTest {
        val controller = seekableController(position = 50.seconds)
        setContent { Box(Modifier.width(320.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) } }

        onNodeWithTag("scrubber").performTouchInput {
            val radius = height / 2f
            val travel = width - 2 * radius
            down(Offset(radius + travel * 0.5f, centerY))
            moveTo(Offset(radius + travel * 0.6f, centerY))
            moveTo(Offset(radius + travel * 0.75f, centerY))
            up()
        }

        assertEquals(1, controller.seeks.size, "one seek on release")
        assertNear(75.seconds, controller.seeks.single())
    }

    @Test
    fun dragStartingElsewhereMovesRelatively() = runComposeUiTest {
        val controller = seekableController(position = 50.seconds)
        setContent { Box(Modifier.width(320.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) } }

        onNodeWithTag("scrubber").performTouchInput {
            val radius = height / 2f
            val travel = width - 2 * radius
            // Far from the thumb at 50 %: the touch point must not become the position.
            down(Offset(radius + travel * 0.1f, centerY))
            moveTo(Offset(radius + travel * 0.15f, centerY))
            moveTo(Offset(radius + travel * 0.2f, centerY))
            up()
        }

        assertNear(60.seconds, controller.seeks.single())
    }

    @Test
    fun touchTargetExtendsAboveTheVisibleScrubber() = runComposeUiTest {
        val controller = seekableController(position = 50.seconds)
        setContent {
            Box(Modifier.width(320.dp).padding(top = 40.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) }
        }

        onNodeWithTag("scrubber").performTouchInput {
            val radius = height / 2f
            val travel = width - 2 * radius
            val above = -12.dp.toPx()
            down(Offset(radius + travel * 0.5f, above))
            moveTo(Offset(radius + travel * 0.6f, above))
            moveTo(Offset(radius + travel * 0.7f, above))
            up()
        }

        assertNear(70.seconds, controller.seeks.single())
    }

    @Test
    fun notSeekableMediaIgnoresDrags() = runComposeUiTest {
        val controller = FakePlayerController(PlayerState(status = PlaybackStatus.Ready, isLive = true))
        setContent { Box(Modifier.width(320.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) } }

        onNodeWithTag("scrubber").performTouchInput {
            down(Offset(width * 0.2f, centerY))
            moveTo(Offset(width * 0.8f, centerY))
            up()
        }

        assertTrue(controller.seeks.isEmpty())
    }

    @Test
    fun buttonsToggleIntentAndMuteWithMatchingLabels() = runComposeUiTest {
        val controller = seekableController()
        // While playing, the scrubber animates every frame, so the clock is driven by hand.
        mainClock.autoAdvance = false
        setContent { PlayerControls(controller) }
        mainClock.advanceTimeByFrame()

        onNodeWithContentDescription("Play").performClick()
        mainClock.advanceTimeByFrame()
        assertTrue(controller.state.value.playWhenReady)
        onNodeWithContentDescription("Pause").performClick()
        mainClock.advanceTimeByFrame()
        assertFalse(controller.state.value.playWhenReady)

        onNodeWithContentDescription("Mute").performClick()
        mainClock.advanceTimeByFrame()
        assertTrue(controller.state.value.isMuted)
        onNodeWithContentDescription("Unmute").assertExists()
    }

    @Test
    fun thumbAdvancesSmoothlyBetweenProgressUpdatesWhilePlaying() = runComposeUiTest {
        val controller = FakePlayerController(
            initialState = PlayerState(
                status = PlaybackStatus.Ready,
                playWhenReady = true,
                duration = 10.seconds,
                seekableRange = Duration.ZERO..10.seconds,
                isSeekable = true,
            ),
            initialProgress = PlaybackProgress(position = 5.7.seconds),
        )
        mainClock.autoAdvance = false
        setContent { Box(Modifier.width(320.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) } }
        mainClock.advanceTimeByFrame()

        // No progress update arrives within these 400 ms, yet the position keeps moving with playback.
        mainClock.advanceTimeBy(400)

        onNodeWithTag("scrubber").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "6 seconds of 10 seconds"))
    }

    @Test
    fun thumbDoesNotAdvanceWhilePaused() = runComposeUiTest {
        val controller = seekableController(position = 50.seconds)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.width(320.dp)) { PlayerScrubber(controller, Modifier.testTag("scrubber")) } }
        mainClock.advanceTimeByFrame()

        mainClock.advanceTimeBy(1_200)

        onNodeWithTag("scrubber").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "50 seconds of 1 minute 40 seconds"))
    }

    @Test
    fun endedShowsReplayAndRestarts() = runComposeUiTest {
        val controller = FakePlayerController(PlayerState(status = PlaybackStatus.Ended))
        setContent { PlayPauseButton(controller) }

        onNodeWithContentDescription("Play").assertDoesNotExist()
        onNodeWithContentDescription("Replay").performClick()

        assertEquals(listOf<FakePlayerController.Call>(FakePlayerController.Call.Play), controller.calls)
    }

    @Test
    fun buttonTouchTargetExtendsBeyondTheVisibleCircle() = runComposeUiTest {
        val controller = seekableController()
        setContent { Box(Modifier.padding(24.dp)) { MuteButton(controller) } }

        onNodeWithContentDescription("Mute").performTouchInput {
            // 6 dp left of the 32 dp circle, inside the 48 dp touch target.
            click(Offset(-6.dp.toPx(), centerY))
        }

        assertTrue(controller.state.value.isMuted)
    }
}
