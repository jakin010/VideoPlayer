package co.liebi.videoplayer.core.internal

internal enum class SeekZone { Back, Middle, Forward }

/**
 * Which double-tap zone [x] falls in. The centered dead zone is [deadZone] of the width; the rest is split
 * evenly into back (left) and forward (right). Never mirrored for right-to-left layouts (§11).
 */
internal fun seekZoneAt(x: Float, width: Float, deadZone: Float): SeekZone {
    val sideWidth = width * (1f - deadZone.coerceIn(0f, 1f)) / 2f
    return when {
        x < sideWidth -> SeekZone.Back
        x > width - sideWidth -> SeekZone.Forward
        else -> SeekZone.Middle
    }
}
