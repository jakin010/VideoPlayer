package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoTransform

/**
 * The native view for one surface. Only the active surface binds [engine]'s output; inactive ones
 * stay empty so the most recently attached surface always gets the video.
 *
 * While [keepPreviousFrame] is set, the last frame shown stays visible until [engine] renders its own,
 * so switching items goes from video to video without a gap.
 *
 * [rotation] turns the video clockwise by 0, 90, 180 or 270 degrees inside the surface's area, and [transform]
 * pans, zooms and turns it further (see [resolveTransform]). Native video views don't follow Compose's graphics
 * layers, so each platform moves its own view.
 */
@Composable
internal expect fun PlatformVideoSurface(
    engine: PlaybackEngine?,
    isActive: Boolean,
    videoSize: IntSize?,
    contentScale: VideoContentScale,
    keepPreviousFrame: Boolean,
    rotation: Int,
    transform: VideoTransform?,
    modifier: Modifier,
)
