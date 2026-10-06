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
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import kotlin.math.PI
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
    rotation: Int,
    modifier: Modifier,
) {
    val layer = (engine as? AVPlaybackEngine)?.playerLayer?.takeIf { isActive }
    val gravity = contentScale.toVideoGravity()
    UIKitView(
        factory = { VideoLayerHostView() },
        modifier = modifier,
        update = { it.host(layer, gravity, keepPreviousFrame, rotation) },
        onRelease = { it.host(null, gravity, keepPrevious = false, rotation = 0) },
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
    private var rotation = 0

    init {
        clipsToBounds = true
        userInteractionEnabled = false
        // Letterbox bars of a fitted video show this view, not the Compose background behind the interop hole.
        backgroundColor = UIColor.blackColor
    }

    fun host(layer: AVPlayerLayer?, gravity: AVLayerVideoGravity, keepPrevious: Boolean, rotation: Int) {
        if (this.rotation != rotation) {
            this.rotation = rotation
            layoutLayers()
        }
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
        hostedLayer?.let(::place)
        previousLayer?.let(::place)
        CATransaction.commit()
    }

    /**
     * Sizes [layer] to this view, turned by [rotation]. The layer moves between surfaces, so the transform is
     * always set, back to none when not turned. With a transform, `frame` is undefined: bounds and position are set.
     */
    private fun place(layer: AVPlayerLayer) {
        val (width, height) = bounds.useContents { size.width to size.height }
        val turned = rotation % 180 != 0
        layer.setAffineTransform(CGAffineTransformMakeRotation(rotation * PI / 180))
        layer.bounds = CGRectMake(0.0, 0.0, if (turned) height else width, if (turned) width else height)
        layer.position = CGPointMake(width / 2, height / 2)
    }
}
