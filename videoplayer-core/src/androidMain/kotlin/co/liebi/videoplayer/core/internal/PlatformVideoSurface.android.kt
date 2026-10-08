package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoTransform

@Composable
internal actual fun PlatformVideoSurface(
    engine: PlaybackEngine?,
    isActive: Boolean,
    videoSize: IntSize?,
    contentScale: VideoContentScale,
    keepPreviousFrame: Boolean,
    rotation: Int,
    transform: VideoTransform?,
    modifier: Modifier,
) {
    // Both view types keep showing their last frame until the next player draws, so nothing to do for keepPreviousFrame.
    val player = (engine as? ExoPlaybackEngine)?.player?.takeIf { isActive }
    val sourceSize = videoSize?.let { Size(it.width.toFloat(), it.height.toFloat()) }
    PlayerSurface(
        player = player,
        // SurfaceView is the cheapest on battery (§10), but its content ignores view transforms, so a turned or
        // transformed video draws into a TextureView instead.
        surfaceType = if (rotation == 0 && transform == null) SURFACE_TYPE_SURFACE_VIEW else SURFACE_TYPE_TEXTURE_VIEW,
        modifier = modifier
            .then(if (transform == null) Modifier else Modifier.transformed(transform, videoSize, rotation, contentScale))
            .turned(rotation)
            .resizeWithContentScale(contentScale.toComposeContentScale(), sourceSize),
    )
}

/** Applies [transform] around the area's center, in the draw phase, so a changing transform never relayouts. */
private fun Modifier.transformed(transform: VideoTransform, videoSize: IntSize?, rotation: Int, contentScale: VideoContentScale) =
    graphicsLayer {
        val resolved = resolveTransform(transform, size, videoSize, rotation, contentScale)
        scaleX = resolved.scale
        scaleY = resolved.scale
        rotationZ = resolved.rotation
        translationX = resolved.translationX
        translationY = resolved.translationY
    }

private fun VideoContentScale.toComposeContentScale(): ContentScale = when (this) {
    VideoContentScale.Fit -> ContentScale.Fit
    VideoContentScale.Crop -> ContentScale.Crop
    VideoContentScale.Fill -> ContentScale.FillBounds
}
