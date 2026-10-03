package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import co.liebi.videoplayer.core.VideoContentScale

@Composable
internal actual fun PlatformVideoSurface(
    engine: PlaybackEngine?,
    isActive: Boolean,
    videoSize: IntSize?,
    contentScale: VideoContentScale,
    keepPreviousFrame: Boolean,
    modifier: Modifier,
) {
    // A SurfaceView keeps showing its last frame until the next player draws, so nothing to do for keepPreviousFrame.
    val player = (engine as? ExoPlaybackEngine)?.player?.takeIf { isActive }
    PlayerSurface(
        player = player,
        // SurfaceView is the cheapest on battery (§10).
        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        modifier = modifier.resizeWithContentScale(
            contentScale = contentScale.toComposeContentScale(),
            sourceSizeDp = videoSize?.let { Size(it.width.toFloat(), it.height.toFloat()) },
        ),
    )
}

private fun VideoContentScale.toComposeContentScale(): ContentScale = when (this) {
    VideoContentScale.Fit -> ContentScale.Fit
    VideoContentScale.Crop -> ContentScale.Crop
    VideoContentScale.Fill -> ContentScale.FillBounds
}
