package co.liebi.videoplayer.ui.internal

import android.view.OrientationEventListener
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
internal actual fun rememberDeviceTurnedRight(): State<Boolean?> {
    val view = LocalView.current
    val turnedRight = remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(view) {
        // Reads the accelerometer, so it keeps working while the user has locked rotation.
        val listener = object : OrientationEventListener(view.context) {
            override fun onOrientationChanged(orientation: Int) {
                // The listener counts clockwise; the display's rotation is the device's counterclockwise turn.
                turnedRight.value = orientation != ORIENTATION_UNKNOWN &&
                    isTurnedRight(deviceTurn = (360 - orientation) % 360, screenTurn = view.display.screenTurn())
            }
        }
        if (listener.canDetectOrientation()) listener.enable() else turnedRight.value = false
        onDispose { listener.disable() }
    }
    return turnedRight
}

private fun android.view.Display?.screenTurn(): Int = when (this?.rotation) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
