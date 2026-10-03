package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.VideoContentScale

/**
 * The native view for one surface. Only the active surface binds [engine]'s output; inactive ones
 * stay empty so the most recently attached surface always gets the video.
 *
 * While [keepPreviousFrame] is set, the last frame shown stays visible until [engine] renders its own,
 * so switching items goes from video to video without a gap.
 */
@Composable
internal expect fun PlatformVideoSurface(
    engine: PlaybackEngine?,
    isActive: Boolean,
    videoSize: IntSize?,
    contentScale: VideoContentScale,
    keepPreviousFrame: Boolean,
    modifier: Modifier,
)
