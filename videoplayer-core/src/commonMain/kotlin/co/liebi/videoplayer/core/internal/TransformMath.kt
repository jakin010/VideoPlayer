package co.liebi.videoplayer.core.internal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Where a [VideoTransform] puts the video, as the platforms apply it: scaled by [scale] and turned clockwise by
 * [rotation] degrees around the area's center, then moved by [translationX] and [translationY].
 */
internal data class ResolvedTransform(
    val scale: Float,
    val rotation: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Resolves [transform] for an [area], in the units of the result (pixels or points), showing a video of
 * [videoSize] (`null` while unknown) turned by [turn] degrees with [contentScale].
 */
internal fun resolveTransform(
    transform: VideoTransform,
    area: Size,
    videoSize: IntSize?,
    turn: Int,
    contentScale: VideoContentScale,
): ResolvedTransform {
    if (area.width <= 0f || area.height <= 0f) return ResolvedTransform(1f, 0f, 0f, 0f)
    val shown = shownVideoSize(area, videoSize, turn, contentScale)
    var zoom = transform.zoom
    var rotation = (transform.rotation % 360f + 540f) % 360f - 180f
    val bounded = !transform.allowOutOfBounds

    if (bounded) {
        zoom = max(zoom, 1f)
        // Absorbs float rounding where the video exactly spans the area, without leaving a visible sliver.
        val tolerance = max(area.width, area.height) * RelativeTolerance
        // Only the sides the zoomed video covers unturned have to stay covered.
        val coversWidth = shown.width * zoom >= area.width - tolerance
        val coversHeight = shown.height * zoom >= area.height - tolerance
        fun staysCovered(degrees: Float) =
            (!coversWidth || shown.width * zoom >= area.spanAlongVideoX(degrees) - tolerance) &&
                (!coversHeight || shown.height * zoom >= area.spanAlongVideoY(degrees) - tolerance)
        if (!staysCovered(rotation)) {
            // The largest turn toward the requested one that keeps the area covered.
            var allowed = 0f
            var blocked = rotation
            repeat(BisectionSteps) {
                val middle = (allowed + blocked) / 2
                if (staysCovered(middle)) allowed = middle else blocked = middle
            }
            rotation = allowed
        }
    }
    // The point of the video, as fractions of its width and height, that ends up at the area's center.
    val focusX = focus(transform.panX, shown.width * zoom, area.spanAlongVideoX(rotation), bounded)
    val focusY = focus(transform.panY, shown.height * zoom, area.spanAlongVideoY(rotation), bounded)

    // The focus point starts this far from the center; the translation brings it back after zooming and turning.
    val offsetX = (focusX - 0.5f) * shown.width
    val offsetY = (focusY - 0.5f) * shown.height
    val cos = cos(rotation.toRadians())
    val sin = sin(rotation.toRadians())
    return ResolvedTransform(
        scale = zoom,
        rotation = rotation,
        translationX = -zoom * (cos * offsetX - sin * offsetY),
        translationY = -zoom * (sin * offsetX + cos * offsetY),
    )
}

/**
 * The video's size in the area as the content scale shows it, turned by [turn] degrees but before any
 * transform; the area while the size is unknown.
 */
internal fun shownVideoSize(area: Size, videoSize: IntSize?, turn: Int, contentScale: VideoContentScale): Size {
    if (videoSize == null || videoSize.width <= 0 || videoSize.height <= 0 || contentScale == VideoContentScale.Fill) return area
    val turned = turn % 180 != 0
    val width = (if (turned) videoSize.height else videoSize.width).toFloat()
    val height = (if (turned) videoSize.width else videoSize.height).toFloat()
    val widthScale = area.width / width
    val heightScale = area.height / height
    val scale = if (contentScale == VideoContentScale.Fit) min(widthScale, heightScale) else max(widthScale, heightScale)
    return Size(width * scale, height * scale)
}

/** How far the area spans along the video's width when the video is turned by [degrees]. */
private fun Size.spanAlongVideoX(degrees: Float): Float =
    width * abs(cos(degrees.toRadians())) + height * abs(sin(degrees.toRadians()))

/** How far the area spans along the video's height when the video is turned by [degrees]. */
private fun Size.spanAlongVideoY(degrees: Float): Float =
    width * abs(sin(degrees.toRadians())) + height * abs(cos(degrees.toRadians()))

private fun Float.toRadians(): Float = this * PI.toFloat() / 180f

/**
 * The focus along one side: the point of the zoomed video ([videoLength]) at the middle of the area's [span].
 * [pan] 0 puts the video's start (left or top edge) on the span's start, 1 its end on the span's end, 0.5 centers
 * it, and values in between move it evenly. Out of bounds, a pan below 0 moves the video's start into the span by
 * that much of the video's length, and one above 1 its end; [bounded], pans stay within 0..1.
 */
private fun focus(pan: Float, videoLength: Float, span: Float, bounded: Boolean): Float {
    // The fraction of the video the span shows.
    val shown = span / videoLength
    val position = if (bounded) pan.coerceIn(0f, 1f) else pan
    // The point of the video at the span's start.
    val start = when {
        position < 0f -> position
        position > 1f -> 1f - shown + (position - 1f)
        else -> position * (1f - shown)
    }
    return start + shown / 2f
}

/** A ten-thousandth of the area: far below a pixel, far above float rounding. */
private const val RelativeTolerance = 1e-4f

private const val BisectionSteps = 24
