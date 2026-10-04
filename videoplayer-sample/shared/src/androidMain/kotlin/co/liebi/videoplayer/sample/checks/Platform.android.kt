package co.liebi.videoplayer.sample.checks

import java.lang.ref.WeakReference

internal actual fun <T : Any> weakRef(referent: T): WeakRef<T> {
    val ref = WeakReference(referent)
    return WeakRef { ref.get() }
}

// What LeakCanary does: a GC request, a pause for reference queues, then finalizers.
internal actual fun collectGarbage() {
    Runtime.getRuntime().gc()
    Thread.sleep(100)
    System.runFinalization()
}
