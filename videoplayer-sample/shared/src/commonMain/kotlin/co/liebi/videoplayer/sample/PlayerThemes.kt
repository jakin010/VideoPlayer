package co.liebi.videoplayer.sample

import androidx.compose.ui.graphics.Color
import co.liebi.videoplayer.ui.PlayerControlsDefaults
import co.liebi.videoplayer.ui.VideoPlayerTheme

private val Amber = Color(0xFFFFB300)

/** Player themes the Player tab switches between while a video plays, to show that a new theme applies right away. */
internal val PlayerThemes: List<Pair<String, VideoPlayerTheme>> = listOf(
    "Default" to VideoPlayerTheme(),
    "Amber" to VideoPlayerTheme(
        colors = PlayerControlsDefaults.colors(
            contentColor = Amber,
            playedTrackColor = Amber,
            thumbColor = Amber,
            bufferedTrackColor = Amber.copy(alpha = 0.3f),
        ),
    ),
    "Light" to VideoPlayerTheme(
        colors = PlayerControlsDefaults.colors(
            contentColor = Color.Black,
            buttonContainerColor = Color.White.copy(alpha = 0.8f),
            playedTrackColor = Color.Black,
            bufferedTrackColor = Color.Black.copy(alpha = 0.3f),
            remainingTrackColor = Color.White.copy(alpha = 0.6f),
            thumbColor = Color.Black,
        ),
    ),
)
