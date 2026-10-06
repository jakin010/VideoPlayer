package co.liebi.videoplayer.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.ic_play
import co.liebi.videoplayer.ui.generated.resources.ic_replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VideoPlayerThemeTest {

    @Test
    fun themesWithTheSameContentAreEqual() {
        // Equal themes don't restyle anything when an app provides a new but identical instance.
        assertEquals(VideoPlayerTheme(), VideoPlayerTheme())
        assertEquals(VideoPlayerIcon(Res.drawable.ic_play, 10.dp), VideoPlayerIcon(Res.drawable.ic_play, 10.dp))
    }

    @Test
    fun iconsDifferByDrawableSizeAndTint() {
        val icon = VideoPlayerIcon(Res.drawable.ic_play, 10.dp)
        assertNotEquals(icon, VideoPlayerIcon(Res.drawable.ic_replay, 10.dp))
        assertNotEquals(icon, VideoPlayerIcon(Res.drawable.ic_play, 12.dp))
        assertNotEquals(icon, VideoPlayerIcon(Res.drawable.ic_play, 10.dp, tinted = false))
    }

    @Test
    fun copyChangesOnlyWhatIsGiven() {
        val theme = VideoPlayerTheme().copy(colors = PlayerControlsDefaults.colors(contentColor = Color.Red))
        assertEquals(VideoPlayerIcons(), theme.icons)
        assertEquals(Color.Red, theme.colors.contentColor)
        assertEquals(PlayerControlsDefaults.colors().thumbColor, theme.colors.thumbColor)
    }

    @Test
    fun anIconWithoutAHeightIsSquare() {
        val icon = VideoPlayerIcon(Res.drawable.ic_play, 14.dp)
        assertEquals(14.dp, icon.height)
    }
}
