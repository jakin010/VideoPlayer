package co.liebi.videoplayer.ui.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIWindowScene

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun rememberDeviceTurnedRight(): State<Boolean?> {
    val turnedRight = remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(Unit) {
        // The accelerometer keeps reporting while the user has locked rotation; UIDevice's orientation doesn't.
        val motion = CMMotionManager()
        if (motion.accelerometerAvailable) {
            motion.accelerometerUpdateInterval = 0.2
            motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
                val turn = data?.acceleration?.useContents { deviceTurnFromGravity(x, y) }
                turnedRight.value = turn != null && isTurnedRight(turn, screenTurn())
            }
        } else {
            turnedRight.value = false
        }
        onDispose { motion.stopAccelerometerUpdates() }
    }
    return turnedRight
}

/** The interface's counterclockwise turn: landscape right has the device turned counterclockwise. */
private fun screenTurn(): Int {
    val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull { it is UIWindowScene } as? UIWindowScene
    return when (scene?.interfaceOrientation) {
        UIInterfaceOrientationLandscapeRight -> 90
        UIInterfaceOrientationPortraitUpsideDown -> 180
        UIInterfaceOrientationLandscapeLeft -> 270
        else -> 0
    }
}
