package co.liebi.videoplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.Presentation
import co.liebi.videoplayer.test.FakePlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class FullscreenUiTest {

    private fun controller(available: Boolean = true, presentation: Presentation = Presentation.Inline) = FakePlayerController(
        initialState = PlayerState(
            status = PlaybackStatus.Ready,
            duration = 100.seconds,
            seekableRange = Duration.ZERO..100.seconds,
            isSeekable = true,
            videoSize = IntSize(1920, 1080),
            isFullscreenAvailable = available,
            presentation = presentation,
        ),
        initialProgress = PlaybackProgress(position = 50.seconds),
    )

    private fun ComposeUiTest.count(label: String): Int =
        onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size

    @Test
    fun fullscreenButtonHidesWithoutAHost() = runComposeUiTest {
        setContent { FullscreenButton(controller(available = false)) }

        assertEquals(0, count("Fullscreen"))
    }

    @Test
    fun fullscreenButtonEntersAndExits() = runComposeUiTest {
        val controller = controller()
        setContent { FullscreenButton(controller) }

        onNodeWithContentDescription("Fullscreen").performClick()
        assertEquals(Presentation.Fullscreen, controller.state.value.presentation)

        onNodeWithContentDescription("Exit fullscreen").performClick()
        assertEquals(Presentation.Inline, controller.state.value.presentation)
        assertEquals(
            listOf<FakePlayerController.Call>(FakePlayerController.Call.EnterFullscreen, FakePlayerController.Call.ExitFullscreen),
            controller.calls,
        )
    }

    @Test
    fun playerControlsShowNoFullscreenButtonOfTheirOwn() = runComposeUiTest {
        setContent { PlayerControls(controller()) }

        assertEquals(1, count("Play"))
        assertEquals(1, count("Mute"))
        assertEquals(0, count("Fullscreen"))
    }

    @Test
    fun videoPlayerShowsTheFullscreenButtonInTheTopRight() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(320.dp, 180.dp).testTag("player")) { VideoPlayer(controller()) } }
        mainClock.advanceTimeByFrame()

        val player = onNodeWithTag("player").getUnclippedBoundsInRoot()
        val button = onNodeWithContentDescription("Fullscreen").getUnclippedBoundsInRoot()
        assertEquals(player.top + 8.dp, button.top)
        assertEquals(player.right - 8.dp, button.right)
    }

    @Test
    fun theFullscreenButtonStaysTopRightInRightToLeftLayouts() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.size(320.dp, 180.dp).testTag("player")) { VideoPlayer(controller()) }
            }
        }
        mainClock.advanceTimeByFrame()

        val player = onNodeWithTag("player").getUnclippedBoundsInRoot()
        assertEquals(player.right - 8.dp, onNodeWithContentDescription("Fullscreen").getUnclippedBoundsInRoot().right)
    }

    @Test
    fun theFullscreenButtonHidesWithTheControls() = runComposeUiTest {
        val controller = controller()
        controller.play()
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(320.dp, 180.dp)) { VideoPlayer(controller) } }
        mainClock.advanceTimeByFrame()
        assertEquals(1, count("Fullscreen"))

        mainClock.advanceTimeBy(3_500)

        assertEquals(0, count("Fullscreen"))
        assertEquals(0, count("Pause"))
    }

    @Test
    fun fullscreenShowsTheDefaultControlsWithAnExitButtonInTheTopRight() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(390.dp, 844.dp)) { FullscreenContent(controller) } }
        mainClock.advanceTimeByFrame()

        assertEquals(1, count("Play"))
        assertEquals(1, count("Mute"))
        val exit = onNodeWithContentDescription("Exit fullscreen").getUnclippedBoundsInRoot()
        assertEquals(390.dp - 8.dp, exit.right)
        onNodeWithContentDescription("Exit fullscreen").performClick()

        assertEquals(Presentation.Inline, controller.state.value.presentation)
    }

    @Test
    fun customControlsReplaceTheOverlayButGesturesStay() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(390.dp, 844.dp).testTag("host")) {
                FullscreenContent(controller, controls = { player, _ -> BasicText("Custom ${player.id}") })
            }
        }
        mainClock.advanceTimeByFrame()

        onNodeWithText("Custom fake-player").assertExists()
        assertEquals(0, count("Play"))

        onNodeWithTag("host").performTouchInput {
            val right = Offset(width * 0.85f, centerY)
            click(right)
            advanceEventTime(100)
            click(right)
        }
        mainClock.advanceTimeByFrame()

        assertTrue(FakePlayerController.Call.SeekTo(60.seconds) in controller.calls, "double-tap seek still works: ${controller.calls}")
    }
}
