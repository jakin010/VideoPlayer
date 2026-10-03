package co.liebi.videoplayer.core.internal

import co.liebi.videoplayer.core.BufferingConfig
import platform.Foundation.NSLog
import platform.Foundation.NSThread
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal actual fun createPlatformEngine(buffering: BufferingConfig): PlaybackEngine = AVPlaybackEngine(buffering)

internal actual fun logWarning(message: String) {
    // Kotlin strings passed as NSLog varargs crash, so the message becomes the format with % escaped.
    NSLog("[LiebiVideoPlayer] ${message.replace("%", "%%")}")
}

internal fun onMainThread(block: () -> Unit) {
    if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue(), block)
}
