package co.liebi.videoplayer.core.internal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoTransform
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayerLayer
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
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
    transform: VideoTransform?,
    modifier: Modifier,
) {
    val layer = (engine as? AVPlaybackEngine)?.playerLayer?.takeIf { isActive }
    val placement = Placement(rotation, transform, videoSize, contentScale)
    UIKitView(
        factory = { VideoLayerHostView() },
        modifier = modifier,
        update = { it.host(layer, keepPreviousFrame, placement) },
        onRelease = { it.host(null, keepPrevious = false, Placement()) },
        // Not placed as an overlay, so Compose content such as controls draws above the video.
        properties = UIKitInteropProperties(isInteractive = false, isNativeAccessibilityEnabled = false),
    )
}

/** How the hosted layers sit in the view: turned by [rotation], then moved by [transform]. */
private data class Placement(
    val rotation: Int = 0,
    val transform: VideoTransform? = null,
    val videoSize: IntSize? = null,
    val contentScale: VideoContentScale = VideoContentScale.Crop,
)

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
    private var placement = Placement()

    init {
        clipsToBounds = true
        userInteractionEnabled = false
        // Letterbox bars of a fitted video show this view, not the Compose background behind the interop hole.
        backgroundColor = UIColor.blackColor
    }

    fun host(layer: AVPlayerLayer?, keepPrevious: Boolean, placement: Placement) {
        if (this.placement != placement) {
            this.placement = placement
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
        layer?.videoGravity = placement.contentScale.toVideoGravity()
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
     * Sizes [layer] to this view, turned by the placement's rotation, then moved by its transform around the
     * center. The layer moves between surfaces, so the transform is always set, back to none when there is
     * nothing to apply. With a transform, `frame` is undefined: bounds and position are set.
     */
    private fun place(layer: AVPlayerLayer) {
        val (width, height) = bounds.useContents { size.width to size.height }
        val area = Size(width.toFloat(), height.toFloat())
        val turned = placement.rotation % 180 != 0
        var affine = CGAffineTransformMakeRotation(placement.rotation * PI / 180)
        // The layer covers the area, or with a transform the whole video as the content scale shows it: a layer
        // crops the video at its own bounds, so moving an area-sized layer would uncover its edge, not the video's.
        var layerSize = area
        placement.transform?.let { transform ->
            layerSize = shownVideoSize(area, placement.videoSize, placement.rotation, placement.contentScale)
            val resolved = resolveTransform(transform, area, placement.videoSize, placement.rotation, placement.contentScale)
            val scale = resolved.scale.toDouble()
            affine = CGAffineTransformConcat(affine, CGAffineTransformMakeScale(scale, scale))
            affine = CGAffineTransformConcat(affine, CGAffineTransformMakeRotation(resolved.rotation * PI / 180))
            affine = CGAffineTransformConcat(
                affine,
                CGAffineTransformMakeTranslation(resolved.translationX.toDouble(), resolved.translationY.toDouble()),
            )
        }
        layer.setAffineTransform(affine)
        val layerWidth = layerSize.width.toDouble()
        val layerHeight = layerSize.height.toDouble()
        layer.bounds = CGRectMake(0.0, 0.0, if (turned) layerHeight else layerWidth, if (turned) layerWidth else layerHeight)
        layer.position = CGPointMake(width / 2, height / 2)
    }
}
