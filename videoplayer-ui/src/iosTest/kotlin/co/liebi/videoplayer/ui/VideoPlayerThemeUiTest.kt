package co.liebi.videoplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.core.Presentation
import co.liebi.videoplayer.test.FakePlayerController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class VideoPlayerThemeUiTest {

    /** Stands in for an app's icon: records whether it is drawn and with which tint. */
    private class ProbePainter : Painter() {
        var draws = 0
        var colorFilter: ColorFilter? = null
        override val intrinsicSize: Size = Size.Unspecified

        override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
            this.colorFilter = colorFilter
            return true
        }

        override fun DrawScope.onDraw() {
            draws++
        }
    }

    private fun pausedController(presentation: Presentation = Presentation.Inline) = FakePlayerController(
        initialState = PlayerState(
            status = PlaybackStatus.Ready,
            duration = 100.seconds,
            presentation = presentation,
            isFullscreenAvailable = true,
        ),
        initialProgress = PlaybackProgress(position = 10.seconds),
    )

    private fun themeWithPlay(icon: VideoPlayerIcon, contentColor: Color = Color.White) = VideoPlayerTheme(
        colors = PlayerControlsDefaults.colors(contentColor = contentColor),
        icons = VideoPlayerIcons().copy(play = icon),
    )

    @Test
    fun aNewThemeAppliesWhileShowing() = runComposeUiTest {
        val controller = pausedController()
        val first = ProbePainter()
        val second = ProbePainter()
        var theme by mutableStateOf(themeWithPlay(VideoPlayerIcon(first), Color.Red))
        setContent { ProvideVideoPlayerTheme(theme) { PlayPauseButton(controller) } }
        waitForIdle()
        assertTrue(first.draws > 0, "the theme's icon is drawn")
        assertEquals(ColorFilter.tint(Color.Red), first.colorFilter)

        theme = themeWithPlay(VideoPlayerIcon(second), Color.Green)
        waitForIdle()
        assertTrue(second.draws > 0, "the new theme's icon replaces the old one")
        assertEquals(ColorFilter.tint(Color.Green), second.colorFilter)
    }

    @Test
    fun anUntintedIconKeepsItsOwnColors() = runComposeUiTest {
        val probe = ProbePainter()
        setContent {
            ProvideVideoPlayerTheme(themeWithPlay(VideoPlayerIcon(probe, tinted = false))) { PlayPauseButton(pausedController()) }
        }
        waitForIdle()
        assertTrue(probe.draws > 0)
        assertNull(probe.colorFilter)
    }

    @Test
    fun colorsPassedToAControlWinOverTheTheme() = runComposeUiTest {
        val probe = ProbePainter()
        setContent {
            ProvideVideoPlayerTheme(themeWithPlay(VideoPlayerIcon(probe), Color.Red)) {
                PlayPauseButton(pausedController(), colors = PlayerControlsDefaults.colors(contentColor = Color.Blue))
            }
        }
        waitForIdle()
        assertEquals(ColorFilter.tint(Color.Blue), probe.colorFilter)
    }

    @Test
    fun theFullscreenPlayerUsesTheTheme() = runComposeUiTest {
        val exit = ProbePainter()
        val rotateLeft = ProbePainter()
        val theme = VideoPlayerTheme(
            colors = PlayerControlsDefaults.colors(contentColor = Color.Yellow),
            icons = VideoPlayerIcons().copy(exitFullscreen = VideoPlayerIcon(exit), rotateLeft = VideoPlayerIcon(rotateLeft)),
        )
        setContent {
            ProvideVideoPlayerTheme(theme) {
                Box(Modifier.size(390.dp, 844.dp)) {
                    FullscreenContent(pausedController(Presentation.Fullscreen), rotation = FullscreenRotation(autoRotate = false))
                }
            }
        }
        waitForIdle()
        assertTrue(exit.draws > 0)
        assertTrue(rotateLeft.draws > 0)
        assertEquals(ColorFilter.tint(Color.Yellow), exit.colorFilter)
    }
}
