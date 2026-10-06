package co.liebi.videoplayer.core.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Lays the content out turned clockwise by [degrees] (a multiple of 90) around the center, with width and height
 * swapped when turned a quarter, so it covers the same area. Touches inside arrive in the turned frame. The node
 * stays the same at every angle, so turning doesn't restart the content's pointer input or views.
 */
internal fun Modifier.turned(degrees: Int): Modifier = layout { measurable, constraints ->
    if (degrees == 0) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(
        if (degrees % 180 != 0) Constraints.fixed(height, width) else Constraints.fixed(width, height),
    )
    layout(width, height) {
        placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
            rotationZ = degrees.toFloat()
        }
    }
}
