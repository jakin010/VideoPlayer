package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import co.liebi.videoplayer.core.VideoContentScale
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView

@Composable
internal actual fun PlatformVideoSurface(
    engine: PlaybackEngine?,
    isActive: Boolean,
    videoSize: IntSize?,
    contentScale: VideoContentScale,
    keepPreviousFrame: Boolean,
    modifier: Modifier,
) {
    val layer = (engine as? AVPlaybackEngine)?.playerLayer?.takeIf { isActive }
    val gravity = contentScale.toVideoGravity()
    UIKitView(
        factory = { VideoLayerHostView() },
        modifier = modifier,
        update = { it.host(layer, gravity, keepPreviousFrame) },
        onRelease = { it.host(null, gravity, keepPrevious = false) },
        // Not placed as an overlay, so Compose content such as controls draws above the video.
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}

private fun VideoContentScale.toVideoGravity(): AVLayerVideoGravity = when (this) {
    VideoContentScale.Fit -> AVLayerVideoGravityResizeAspect
    VideoContentScale.Crop -> AVLayerVideoGravityResizeAspectFill
    VideoContentScale.Fill -> AVLayerVideoGravityResize
}

/**
 * Hosts the engine's AVPlayerLayer while this surface is the active one. When the layer changes while
 * [host]'s `keepPrevious` is set, the old layer stays underneath, still showing its last frame, until
 * the new one has a frame of its own (the new layer is transparent until then).
 */
@OptIn(ExperimentalForeignApi::class)
private class VideoLayerHostView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private var hostedLayer: AVPlayerLayer? = null
    private var previousLayer: AVPlayerLayer? = null

    init {
        clipsToBounds = true
        userInteractionEnabled = false
        backgroundColor = UIColor.clearColor
    }

    fun host(layer: AVPlayerLayer?, gravity: AVLayerVideoGravity, keepPrevious: Boolean) {
        if (hostedLayer !== layer) {
            val old = hostedLayer
            hostedLayer = layer
            if (previousLayer === layer) previousLayer = null
            if (old != null) {
                if (keepPrevious && layer != null) {
                    detach(previousLayer)
                    previousLayer = old
                } else {
                    detach(old)
                }
            }
            // Added on top, so the kept layer shows through until this one renders.
            layer?.let { this.layer.addSublayer(it) }
            layoutLayers()
        }
        if (!keepPrevious) {
            detach(previousLayer)
            previousLayer = null
        }
        layer?.videoGravity = gravity
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        layoutLayers()
    }

    /** The layer may already have moved to another surface; only detach it from this one. */
    private fun detach(layer: AVPlayerLayer?) {
        if (layer != null && layer !== hostedLayer && layer.superlayer == this.layer) layer.removeFromSuperlayer()
    }

    private fun layoutLayers() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        hostedLayer?.frame = bounds
        previousLayer?.frame = bounds
        CATransaction.commit()
    }
}
