package co.liebi.videoplayer.ui.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputEventHandler
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DpTouchBoundsExpansion
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.TouchBoundsExpansion
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/**
 * Like `pointerInput(key)`, but the touch target is grown by [expansion] without taking layout space.
 * Touches inside a sibling's real bounds still go to that sibling first.
 */
internal fun Modifier.expandedPointerInput(
    key: Any?,
    expansion: DpTouchBoundsExpansion,
    handler: PointerInputEventHandler,
): Modifier = this then ExpandedPointerInputElement(key, expansion, handler)

private class ExpandedPointerInputElement(
    private val key: Any?,
    private val expansion: DpTouchBoundsExpansion,
    private val handler: PointerInputEventHandler,
) : ModifierNodeElement<ExpandedPointerInputNode>() {

    override fun create() = ExpandedPointerInputNode(key, expansion, handler)

    override fun update(node: ExpandedPointerInputNode) = node.update(key, expansion, handler)

    // The handler is excluded on purpose, matching pointerInput(key): it restarts only when the key changes.
    override fun equals(other: Any?): Boolean =
        other is ExpandedPointerInputElement && other.key == key && other.expansion == expansion

    override fun hashCode(): Int = 31 * key.hashCode() + expansion.hashCode()

    override fun InspectorInfo.inspectableProperties() {
        name = "expandedPointerInput"
        properties["key"] = key
        properties["expansion"] = expansion
    }
}

private class ExpandedPointerInputNode(
    private var key: Any?,
    private var expansion: DpTouchBoundsExpansion,
    handler: PointerInputEventHandler,
) : DelegatingNode(), PointerInputModifierNode {

    private val pointerInput = delegate(SuspendingPointerInputModifierNode(handler))

    override val touchBoundsExpansion: TouchBoundsExpansion
        get() = expansion.roundToTouchBoundsExpansion(requireDensity())

    fun update(key: Any?, expansion: DpTouchBoundsExpansion, handler: PointerInputEventHandler) {
        this.expansion = expansion
        if (this.key != key) {
            this.key = key
            pointerInput.pointerInputEventHandler = handler
        }
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) =
        pointerInput.onPointerEvent(pointerEvent, pass, bounds)

    override fun onCancelPointerInput() = pointerInput.onCancelPointerInput()
}
