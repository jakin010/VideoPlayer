package co.liebi.videoplayer.core.internal

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransformMathTest {

    private val square = Size(100f, 100f)
    private val wide = IntSize(1920, 1080)

    private fun resolve(transform: VideoTransform, contentScale: VideoContentScale = VideoContentScale.Crop, video: IntSize? = wide) =
        resolveTransform(transform, square, video, turn = 0, contentScale)

    /** Where the video point [panX], [panY] ends up, relative to the area's center. */
    private fun ResolvedTransform.positionOf(panX: Float, panY: Float, shown: Size): Pair<Float, Float> {
        val x = (panX - 0.5f) * shown.width * scale
        val y = (panY - 0.5f) * shown.height * scale
        val radians = rotation * PI.toFloat() / 180f
        return (cos(radians) * x - sin(radians) * y + translationX) to (sin(radians) * x + cos(radians) * y + translationY)
    }

    private fun assertNear(expected: Float, actual: Float, message: String? = null, tolerance: Float = 0.01f) =
        assertEquals(expected, actual, tolerance, message)

    @Test
    fun theDefaultTransformChangesNothing() {
        val resolved = resolve(VideoTransform())
        assertNear(1f, resolved.scale)
        assertNear(0f, resolved.rotation)
        assertNear(0f, resolved.translationX)
        assertNear(0f, resolved.translationY)
    }

    @Test
    fun panAlignsTheVideoLeftCenterOrRight() {
        val shown = Size(177.78f, 100f)
        val (left, _) = resolve(VideoTransform(panX = 0f)).positionOf(0f, 0.5f, shown)
        assertNear(-50f, left, "0 puts the video's left edge on the area's left edge")
        assertNear(0f, resolve(VideoTransform(panX = 0.5f)).translationX, "0.5 centers it")
        val (right, _) = resolve(VideoTransform(panX = 1f)).positionOf(1f, 0.5f, shown)
        assertNear(50f, right, "1 puts its right edge on the area's right edge")
    }

    @Test
    fun panMovesEvenlyInBetween() {
        val left = resolve(VideoTransform(panX = 0f)).translationX
        assertNear(left / 2, resolve(VideoTransform(panX = 0.25f)).translationX, "halfway between left and center")
        assertNear(-left / 2, resolve(VideoTransform(panX = 0.75f)).translationX)
    }

    @Test
    fun aWideVideoInASquareAreaPansSidewaysOnly() {
        // The video's height exactly spans the square at 1x, so it can't move up or down.
        assertNear(0f, resolve(VideoTransform(panY = 0f)).translationY)
        assertNear(0f, resolve(VideoTransform(panY = 1f)).translationY)
    }

    @Test
    fun zoomedInThePanReachesTheTopToo() {
        val shown = Size(177.78f, 100f)
        val (x, y) = resolve(VideoTransform(panX = 0f, panY = 0f, zoom = 2f)).positionOf(0f, 0f, shown)
        assertNear(-50f, x)
        assertNear(-50f, y)
    }

    @Test
    fun aFittedVideoMovesBetweenItsBars() {
        // Fitted, the 16:9 video leaves bars above and below in the square; panning moves it between them.
        val shown = Size(100f, 56.25f)
        val (_, top) = resolve(VideoTransform(panY = 0f), VideoContentScale.Fit).positionOf(0.5f, 0f, shown)
        assertNear(-50f, top)
        val (_, bottom) = resolve(VideoTransform(panY = 1f), VideoContentScale.Fit).positionOf(0.5f, 1f, shown)
        assertNear(50f, bottom)
    }

    @Test
    fun withinBoundsPansBeyondTheEndsCountAsTheEnds() {
        assertNear(resolve(VideoTransform(panX = 0f)).translationX, resolve(VideoTransform(panX = -0.25f)).translationX)
        assertNear(resolve(VideoTransform(panX = 1f)).translationX, resolve(VideoTransform(panX = 1.25f)).translationX)
    }

    @Test
    fun outOfBoundsPansBeyondTheEndsMoveTheVideoFurther() {
        val shown = Size(177.78f, 100f)
        // -0.25 leaves a quarter of the video's width of background before its left edge, 1.25 after its right edge.
        val (left, _) = resolve(VideoTransform(panX = -0.25f, allowOutOfBounds = true)).positionOf(0f, 0.5f, shown)
        assertNear(-50f + 0.25f * 177.78f, left)
        val (right, _) = resolve(VideoTransform(panX = 1.25f, allowOutOfBounds = true)).positionOf(1f, 0.5f, shown)
        assertNear(50f - 0.25f * 177.78f, right)
        // Where the video exactly spans the area, it moves too.
        val (_, top) = resolve(VideoTransform(panY = -0.25f, allowOutOfBounds = true)).positionOf(0.5f, 0f, shown)
        assertNear(-25f, top)
    }

    @Test
    fun outOfBoundsZeroToOneWorksAsWithinBounds() {
        for (pan in listOf(0f, 0.25f, 0.5f, 1f)) {
            assertNear(
                resolve(VideoTransform(panX = pan)).translationX,
                resolve(VideoTransform(panX = pan, allowOutOfBounds = true)).translationX,
                "pan $pan",
            )
        }
    }

    @Test
    fun aZoomedOutVideoMovesBetweenTheEdges() {
        // Out of bounds at half size, the video is smaller than the square: 0 and 1 put it against the edges.
        val shown = Size(177.78f, 100f)
        val (left, _) = resolve(VideoTransform(panX = 0f, zoom = 0.5f, allowOutOfBounds = true)).positionOf(0f, 0.5f, shown)
        assertNear(-50f, left)
        val (_, bottom) = resolve(VideoTransform(panY = 1f, zoom = 0.5f, allowOutOfBounds = true)).positionOf(0.5f, 1f, shown)
        assertNear(50f, bottom)
    }

    @Test
    fun zoomStaysAtOneOrMoreWithinBounds() {
        assertEquals(1f, resolve(VideoTransform(zoom = 0.5f)).scale)
        assertEquals(0.5f, resolve(VideoTransform(zoom = 0.5f, allowOutOfBounds = true)).scale)
    }

    @Test
    fun rotationStopsBeforeACornerShows() {
        // At 1x the video's height exactly spans the square, so any turn would show a corner.
        assertNear(0f, resolve(VideoTransform(rotation = 30f)).rotation)
        // At 1.2x it may turn until the square's span along the video's height reaches 1.2 heights: about 13 degrees.
        assertNear(13.05f, resolve(VideoTransform(rotation = 30f, zoom = 1.2f)).rotation)
        assertNear(-13.05f, resolve(VideoTransform(rotation = -30f, zoom = 1.2f)).rotation)
        // Zoomed in far enough, any turn keeps the square covered.
        assertNear(80f, resolve(VideoTransform(rotation = 80f, zoom = 2f)).rotation)
    }

    @Test
    fun outOfBoundsValuesApplyAsGiven() {
        val shown = Size(177.78f, 100f)
        val resolved = resolve(VideoTransform(zoom = 0.5f, rotation = 30f, allowOutOfBounds = true))
        assertNear(0.5f, resolved.scale)
        assertNear(30f, resolved.rotation)
        val (x, y) = resolved.positionOf(0.5f, 0.5f, shown)
        assertNear(0f, x, "the video's center stays at the area's center")
        assertNear(0f, y)
    }

    @Test
    fun rotationIsNormalized() {
        assertNear(10f, resolve(VideoTransform(rotation = 370f, allowOutOfBounds = true)).rotation)
        assertNear(-90f, resolve(VideoTransform(rotation = 270f, allowOutOfBounds = true)).rotation)
    }

    @Test
    fun aTurnedVideoPansAlongItsTurnedShape() {
        // Turned a quarter, the 16:9 video stands 9:16 in the square, so it moves up and down instead of sideways.
        val resolved = resolveTransform(VideoTransform(panX = 0f, panY = 0f), square, wide, turn = 90, VideoContentScale.Crop)
        assertNear(0f, resolved.translationX)
        assertNear(38.89f, resolved.translationY)
    }

    @Test
    fun anUnknownVideoSizeCountsAsFillingTheArea() {
        val resolved = resolve(VideoTransform(panX = 0f), video = null)
        assertNear(0f, resolved.translationX, "a video exactly the area's size can't pan")
    }

    @Test
    fun invalidValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { VideoTransform(zoom = 0f) }
        assertFailsWith<IllegalArgumentException> { VideoTransform(rotation = Float.NaN) }
    }
}
