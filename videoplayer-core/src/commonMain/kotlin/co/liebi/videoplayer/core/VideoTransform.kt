package co.liebi.videoplayer.core

import androidx.compose.runtime.Immutable

/**
 * Pans, zooms and turns the video inside its area; the area itself keeps its size and place. Every value is
 * relative, so one transform suits any area and video size. Only inline players take a transform: fullscreen
 * always shows the whole video.
 *
 * ```
 * VideoPlayer(controller, transform = VideoTransform(panX = 0f, zoom = 1.5f))
 * ```
 *
 * @param panX Where the video sits along its width: 0 puts its left edge on the area's left edge, 0.5 centers it
 *   and 1 puts its right edge on the area's right edge; values in between move it evenly. Within bounds, values
 *   outside 0..1 count as the nearest end. Out of bounds they move the video further: -0.25 leaves a quarter of
 *   the video's width of background before its left edge, 1.25 after its right edge. Turned, it follows the
 *   video's own width.
 * @param panY The same along the video's height: 0 puts its top edge on the area's top edge.
 * @param zoom 1 is the size the content scale gives; 2 shows the video twice as large.
 * @param rotation Clockwise, in degrees.
 * @param allowOutOfBounds When off, the values are limited so the video keeps covering the area wherever it
 *   covers it without a transform: zoom stays at 1 or more, pans stay within 0..1, and rotation stops before a
 *   corner comes into view (zooming in allows more). When on, zoom below 1, any rotation and pans beyond 0..1
 *   apply, and the area's background shows around the video.
 */
@Immutable
public data class VideoTransform(
    val panX: Float = 0.5f,
    val panY: Float = 0.5f,
    val zoom: Float = 1f,
    val rotation: Float = 0f,
    val allowOutOfBounds: Boolean = false,
) {
    init {
        require(zoom > 0f && zoom.isFinite()) { "zoom must be positive" }
        require(panX.isFinite() && panY.isFinite() && rotation.isFinite()) { "pan and rotation must be finite" }
    }
}
