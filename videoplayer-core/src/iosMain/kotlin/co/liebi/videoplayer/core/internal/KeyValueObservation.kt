package co.liebi.videoplayer.core.internal

import co.liebi.videoplayer.core.internal.kvo.LVPKeyValueObserverProtocol
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSThread
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** Observes [keyPath] on [target] and calls [onChange] on the main thread until [invalidate] is called. */
@OptIn(ExperimentalForeignApi::class)
internal class KeyValueObservation(
    private val target: NSObject,
    private val keyPath: String,
    onChange: () -> Unit,
) {
    private var isActive = true
    private val observer = Observer { if (isActive) onChange() }

    init {
        target.addObserver(observer, forKeyPath = keyPath, options = NSKeyValueObservingOptionNew, context = null)
    }

    fun invalidate() {
        if (!isActive) return
        isActive = false
        target.removeObserver(observer, forKeyPath = keyPath)
    }

    private class Observer(private val onChange: () -> Unit) : NSObject(), LVPKeyValueObserverProtocol {
        override fun observeValueForKeyPath(
            keyPath: String?,
            ofObject: Any?,
            change: Map<Any?, *>?,
            context: COpaquePointer?,
        ) {
            // AVFoundation may notify on any thread.
            if (NSThread.isMainThread) onChange() else dispatch_async(dispatch_get_main_queue()) { onChange() }
        }
    }
}
