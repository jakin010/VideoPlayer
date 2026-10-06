package co.liebi.videoplayer.ui.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlin.math.abs

/**
 * Whether the device is held with its top pointing right, relative to the screen, from its motion sensors
 * while composed. `null` until the first reading; `false` while lying flat or without a sensor. Works while
 * the user has locked the screen's rotation.
 */
@Composable
internal expect fun rememberDeviceTurnedRight(): State<Boolean?>

/**
 * Whether the device is turned a quarter clockwise relative to the screen, from how far the device
 * ([deviceTurn]) and the screen ([screenTurn]) are turned counterclockwise from the device's natural position.
 * Readings round to the nearest quarter.
 */
internal fun isTurnedRight(deviceTurn: Int, screenTurn: Int): Boolean = ((deviceTurn - screenTurn).mod(360) + 45) / 90 % 4 == 3

/**
 * The device's counterclockwise turn from gravity along its x axis (right in portrait) and y axis (up in
 * portrait), in g. `null` while it lies flat.
 */
internal fun deviceTurnFromGravity(x: Double, y: Double): Int? = when {
    abs(x) < FlatThreshold && abs(y) < FlatThreshold -> null
    abs(y) >= abs(x) -> if (y < 0) 0 else 180
    // Turned counterclockwise, the device's x axis points up, so gravity pulls along -x.
    else -> if (x < 0) 90 else 270
}

private const val FlatThreshold = 0.5
