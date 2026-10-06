package co.liebi.videoplayer.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.Presentation
import co.liebi.videoplayer.core.SwipeDirection
import co.liebi.videoplayer.core.VideoGestureState
import co.liebi.videoplayer.core.VideoGestures
import co.liebi.videoplayer.core.VideoPlayerSurface
import co.liebi.videoplayer.test.FakePlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class FullscreenUiTest {

    private fun controller(
        available: Boolean = true,
        presentation: Presentation = Presentation.Inline,
        videoSize: IntSize = IntSize(1920, 1080),
    ) = FakePlayerController(
        initialState = PlayerState(
            status = PlaybackStatus.Ready,
            duration = 100.seconds,
            seekableRange = Duration.ZERO..100.seconds,
            isSeekable = true,
            videoSize = videoSize,
            isFullscreenAvailable = available,
            presentation = presentation,
        ),
        initialProgress = PlaybackProgress(position = 50.seconds),
    )

    private fun ComposeUiTest.count(label: String): Int =
        onAllNodes(hasContentDescription(label)).fetchSemanticsNodes().size

    @Test
    fun fullscreenButtonHidesUnlessFullscreenIsEnabled() = runComposeUiTest {
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
        // Not turned, so the top right of the content is the top right of the screen.
        setContent { Box(Modifier.size(390.dp, 844.dp)) { FullscreenContent(controller, rotation = null) } }
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
                FullscreenContent(controller, rotation = null, controls = { _ -> BasicText("Custom controls") })
            }
        }
        mainClock.advanceTimeByFrame()

        onNodeWithText("Custom controls").assertExists()
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

    // region Swipes

    private val FakePlayerController.fullscreenCalls: List<FakePlayerController.Call>
        get() = calls.filter { it == FakePlayerController.Call.EnterFullscreen || it == FakePlayerController.Call.ExitFullscreen }

    private fun ComposeUiTest.showPlayer(controller: FakePlayerController, swipeToFullscreen: Boolean = true) {
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(320.dp, 180.dp).testTag("player")) {
                VideoPlayer(controller, gestures = VideoGestures(swipeToFullscreen = swipeToFullscreen))
            }
        }
        mainClock.advanceTimeByFrame()
    }

    @Test
    fun swipingUpOnThePlayerEntersFullscreen() = runComposeUiTest {
        val controller = controller()
        showPlayer(controller)

        onNodeWithTag("player").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.1f) }
        mainClock.advanceTimeByFrame()

        assertEquals(listOf<FakePlayerController.Call>(FakePlayerController.Call.EnterFullscreen), controller.fullscreenCalls)
    }

    @Test
    fun aShortSwipeUpDoesNothing() = runComposeUiTest {
        val controller = controller()
        showPlayer(controller)

        // Past touch slop, but released before the swipe distance.
        onNodeWithTag("player").performTouchInput { swipeUp(startY = centerY, endY = centerY - 30.dp.toPx()) }
        mainClock.advanceTimeByFrame()

        assertTrue(controller.fullscreenCalls.isEmpty(), controller.calls.toString())
    }

    @Test
    fun swipingUpDoesNothingUnlessFullscreenIsEnabled() = runComposeUiTest {
        val notEnabled = controller(available = false)
        showPlayer(notEnabled)
        onNodeWithTag("player").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.1f) }
        mainClock.advanceTimeByFrame()

        assertTrue(notEnabled.fullscreenCalls.isEmpty())
    }

    @Test
    fun swipingUpDoesNothingWhenTurnedOff() = runComposeUiTest {
        val controller = controller()
        showPlayer(controller, swipeToFullscreen = false)

        onNodeWithTag("player").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.1f) }
        mainClock.advanceTimeByFrame()

        assertTrue(controller.fullscreenCalls.isEmpty())
    }

    @Test
    fun swipingDownInlineIsLeftAlone() = runComposeUiTest {
        val controller = controller()
        showPlayer(controller)

        onNodeWithTag("player").performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.7f) }
        mainClock.advanceTimeByFrame()

        assertTrue(controller.fullscreenCalls.isEmpty())
    }

    @Test
    fun aScrollingParentScrollsExceptForTheClaimedSwipe() = runComposeUiTest {
        val controller = controller()
        val scroll = ScrollState(initial = 100)
        mainClock.autoAdvance = false
        setContent {
            Column(Modifier.size(320.dp, 400.dp).verticalScroll(scroll)) {
                Spacer(Modifier.height(200.dp))
                Box(Modifier.size(320.dp, 180.dp).testTag("player")) { VideoPlayer(controller) }
                Spacer(Modifier.height(1000.dp))
            }
        }
        mainClock.advanceTimeByFrame()

        onNodeWithTag("player").performTouchInput { swipeDown(startY = height * 0.1f, endY = height * 0.7f) }
        mainClock.advanceTimeBy(1_000)
        val scrolled = scroll.value
        assertTrue(scrolled < 100, "swiping down still scrolls the page: $scrolled")
        assertTrue(controller.fullscreenCalls.isEmpty())

        // Last: the fake then reports fullscreen, and with nothing showing it fullscreen the player would take swipes down.
        onNodeWithTag("player").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.1f) }
        mainClock.advanceTimeBy(1_000)
        assertEquals(scrolled, scroll.value, "swiping up on the player belongs to the player")
        assertEquals(listOf<FakePlayerController.Call>(FakePlayerController.Call.EnterFullscreen), controller.fullscreenCalls)
    }

    /** A custom layout with the gesture state hoisted, so the test can read the feedback. */
    private fun ComposeUiTest.showGestures(controller: FakePlayerController): () -> VideoGestureState {
        val state = VideoGestureState()
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(320.dp, 180.dp).testTag("player")) {
                VideoPlayerSurface(controller, Modifier.matchParentSize(), gestureState = state)
                SwipeIndicator(state, Modifier.matchParentSize())
            }
        }
        mainClock.advanceTimeByFrame()
        return { state }
    }

    @Test
    fun aSwipeReportsItsProgressUntilReleased() = runComposeUiTest {
        val controller = controller()
        val gestures = showGestures(controller)

        onNodeWithTag("player").performTouchInput {
            down(center)
            moveBy(Offset(0f, -24.dp.toPx()))
        }
        mainClock.advanceTimeByFrame()
        val halfway = gestures().swipeFeedback
        assertEquals(SwipeDirection.Up, halfway?.direction)
        assertEquals(0.5f, halfway!!.progress, 0.01f)

        onNodeWithTag("player").performTouchInput { moveBy(Offset(0f, -40.dp.toPx())) }
        mainClock.advanceTimeByFrame()
        assertEquals(1f, gestures().swipeFeedback?.progress, "past the distance it stays at 1")

        onNodeWithTag("player").performTouchInput { up() }
        mainClock.advanceTimeByFrame()
        assertNull(gestures().swipeFeedback)
        assertEquals(listOf<FakePlayerController.Call>(FakePlayerController.Call.EnterFullscreen), controller.fullscreenCalls)
    }

    @Test
    fun movingBackBeforeReleasingCancelsTheSwipe() = runComposeUiTest {
        val controller = controller()
        val gestures = showGestures(controller)

        onNodeWithTag("player").performTouchInput {
            down(center)
            moveBy(Offset(0f, -60.dp.toPx()))
        }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("player").performTouchInput { moveBy(Offset(0f, 50.dp.toPx())) }
        mainClock.advanceTimeByFrame()
        assertTrue(gestures().swipeFeedback!!.progress < 0.5f)

        onNodeWithTag("player").performTouchInput { up() }
        mainClock.advanceTimeByFrame()
        assertNull(gestures().swipeFeedback)
        assertTrue(controller.fullscreenCalls.isEmpty())
    }

    @Test
    fun inFullscreenTheFeedbackPointsDown() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        val gestures = showGestures(controller)

        onNodeWithTag("player").performTouchInput {
            down(Offset(centerX, height * 0.2f))
            moveBy(Offset(0f, 24.dp.toPx()))
        }
        mainClock.advanceTimeByFrame()
        assertEquals(SwipeDirection.Down, gestures().swipeFeedback?.direction)
        onNodeWithTag("player").performTouchInput { up() }
    }

    @Test
    fun swipingDownInFullscreenExits() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        mainClock.autoAdvance = false
        setContent { Box(Modifier.size(390.dp, 844.dp).testTag("host")) { FullscreenContent(controller, rotation = null) } }
        mainClock.advanceTimeByFrame()

        onNodeWithTag("host").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.3f) }
        mainClock.advanceTimeByFrame()
        assertTrue(controller.fullscreenCalls.isEmpty(), "swiping up in fullscreen does nothing")

        onNodeWithTag("host").performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.7f) }
        mainClock.advanceTimeByFrame()
        assertEquals(listOf<FakePlayerController.Call>(FakePlayerController.Call.ExitFullscreen), controller.fullscreenCalls)
    }

    @Test
    fun inATurnedViewDownFollowsTheVideo() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        val turn = FullscreenViewRotation(90)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(390.dp, 844.dp).testTag("host")) {
                FullscreenContent(controller, rotation = FullscreenRotation(autoRotate = false), viewRotation = turn)
            }
        }
        mainClock.advanceTimeByFrame()

        // Turned clockwise, the video's bottom lies along the screen's left edge.
        onNodeWithTag("host").performTouchInput { swipe(Offset(width * 0.8f, centerY), Offset(width * 0.2f, centerY)) }
        mainClock.advanceTimeByFrame()

        assertEquals(listOf<FakePlayerController.Call>(FakePlayerController.Call.ExitFullscreen), controller.fullscreenCalls)
    }

    // endregion

    // region Rotation

    private fun ComposeUiTest.showFullscreen(
        controller: FakePlayerController,
        turn: FullscreenViewRotation,
        rotation: FullscreenRotation? = FullscreenRotation(),
        width: Int = 390,
        height: Int = 844,
    ) {
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(width.dp, height.dp)) { FullscreenContent(controller, rotation = rotation, viewRotation = turn) }
        }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
    }

    @Test
    fun aLandscapeVideoOnAPortraitScreenIsTurned() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen), turn)

        assertEquals(90, turn.degrees)
    }

    @Test
    fun aSquareVideoIsNotTurned() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen, videoSize = IntSize(1080, 1080)), turn)

        assertEquals(0, turn.degrees)
    }

    @Test
    fun aVideoMatchingTheScreenIsNotTurned() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen), turn, width = 844, height = 390)

        assertEquals(0, turn.degrees)
    }

    @Test
    fun aPortraitVideoOnALandscapeScreenIsTurned() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen, videoSize = IntSize(1080, 1920)), turn, width = 844, height = 390)

        assertEquals(90, turn.degrees)
    }

    @Test
    fun theRotateButtonsSitLeftOfTheExitButtonAndTurnTheView() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen), turn, rotation = FullscreenRotation(autoRotate = false))

        val exit = onNodeWithContentDescription("Exit fullscreen").getUnclippedBoundsInRoot()
        val right = onNodeWithContentDescription("Rotate right").getUnclippedBoundsInRoot()
        val left = onNodeWithContentDescription("Rotate left").getUnclippedBoundsInRoot()
        assertTrue(left.right < right.left && right.right < exit.left, "rotate left, rotate right, exit")
        assertEquals(exit.top, left.top)

        onNodeWithContentDescription("Rotate right").performClick()
        mainClock.advanceTimeByFrame()
        assertEquals(90, turn.degrees)
    }

    @Test
    fun rotateLeftTurnsCounterclockwise() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen), turn, rotation = FullscreenRotation(autoRotate = false))

        onNodeWithContentDescription("Rotate left").performClick()
        mainClock.advanceTimeByFrame()

        assertEquals(270, turn.degrees)
    }

    @Test
    fun touchesReachTheControlsOfATurnedView() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        val turn = FullscreenViewRotation(90)
        showFullscreen(controller, turn, rotation = FullscreenRotation(autoRotate = false))

        // A real touch, not the accessibility action, so hit testing through the turned layer is exercised.
        onNodeWithContentDescription("Rotate right").performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        assertEquals(180, turn.degrees)

        onNodeWithContentDescription("Exit fullscreen").performTouchInput { click(center) }
        mainClock.advanceTimeByFrame()
        assertTrue(FakePlayerController.Call.ExitFullscreen in controller.calls, controller.calls.toString())
    }

    @Test
    fun rotationCanBeTurnedOff() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen), turn, rotation = null)

        assertEquals(0, turn.degrees)
        assertEquals(0, count("Rotate left") + count("Rotate right"))
        assertEquals(1, count("Exit fullscreen"))
    }

    @Test
    fun theButtonsCanBeHiddenWhileAutoRotateStays() = runComposeUiTest {
        val turn = FullscreenViewRotation()
        showFullscreen(controller(presentation = Presentation.Fullscreen), turn, rotation = FullscreenRotation(showButtons = false))

        assertEquals(90, turn.degrees)
        assertEquals(0, count("Rotate left") + count("Rotate right"))
    }

    @Test
    fun theInlinePlayerHasNoRotateButtons() = runComposeUiTest {
        showPlayer(controller())

        assertEquals(0, count("Rotate left") + count("Rotate right"))
        assertEquals(1, count("Fullscreen"))
    }

    @Test
    fun inATurnedViewDoubleTapSidesFollowTheVideo() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        val turn = FullscreenViewRotation(90)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(390.dp, 844.dp).testTag("host")) {
                FullscreenContent(controller, rotation = FullscreenRotation(autoRotate = false), viewRotation = turn)
            }
        }
        mainClock.advanceTimeByFrame()

        // Turned clockwise, the video's right side lies along the screen's bottom.
        onNodeWithTag("host").performTouchInput {
            val videoRight = Offset(centerX, height * 0.9f)
            click(videoRight)
            advanceEventTime(100)
            click(videoRight)
        }
        mainClock.advanceTimeByFrame()

        assertTrue(FakePlayerController.Call.SeekTo(60.seconds) in controller.calls, controller.calls.toString())
    }

    // endregion

    // region Public parts

    @Test
    fun fullscreenControlsShowTheRotateButtonsOnlyWithARotation() = runComposeUiTest {
        val controller = controller(presentation = Presentation.Fullscreen)
        var rotation by mutableStateOf<FullscreenViewRotation?>(null)
        setContent { FullscreenControls(controller, rotation = rotation) }
        assertEquals(0, count("Rotate left") + count("Rotate right"))
        assertEquals(1, count("Exit fullscreen"))

        rotation = FullscreenViewRotation()
        waitForIdle()
        assertEquals(1, count("Rotate left"))
        assertEquals(1, count("Rotate right"))
    }

    @Test
    fun aFullscreenPlayerWithoutWidthDoesNotCrash() = runComposeUiTest {
        // As inside an expanding animation, before it has any width.
        setContent { Box(Modifier.size(0.dp, 400.dp)) { FullscreenVideoPlayer(controller(presentation = Presentation.Fullscreen)) } }
        waitForIdle()
    }

    @Test
    fun theAppShowsTheFullscreenPlayerWhileThePlayerIsFullscreen() = runComposeUiTest {
        val controller = controller()
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.size(390.dp, 844.dp)) {
                Box(Modifier.size(320.dp, 180.dp)) { VideoPlayer(controller) }
                val state by controller.state.collectAsState()
                if (state.presentation == Presentation.Fullscreen) {
                    FullscreenVideoPlayer(controller, Modifier.testTag("fullscreen"), rotation = null)
                }
            }
        }
        mainClock.advanceTimeByFrame()
        onNodeWithTag("fullscreen").assertDoesNotExist()

        onNodeWithContentDescription("Fullscreen").performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("fullscreen").assertExists()

        onNode(hasContentDescription("Exit fullscreen") and hasAnyAncestor(hasTestTag("fullscreen"))).performClick()
        mainClock.advanceTimeByFrame()
        onNodeWithTag("fullscreen").assertDoesNotExist()
        assertEquals(Presentation.Inline, controller.state.value.presentation)
    }

    // endregion
}
