package co.liebi.videoplayer.ui.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsVoiceOverRunning
import platform.UIKit.UIAccessibilityVoiceOverStatusDidChangeNotification

@Composable
internal actual fun rememberIsScreenReaderEnabled(): Boolean {
    var isEnabled by remember { mutableStateOf(UIAccessibilityIsVoiceOverRunning()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val token = center.addObserverForName(UIAccessibilityVoiceOverStatusDidChangeNotification, null, NSOperationQueue.mainQueue) { _ ->
            isEnabled = UIAccessibilityIsVoiceOverRunning()
        }
        onDispose { center.removeObserver(token) }
    }
    return isEnabled
}
