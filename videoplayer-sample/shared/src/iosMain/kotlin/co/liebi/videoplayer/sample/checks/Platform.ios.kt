package co.liebi.videoplayer.sample.checks

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

@OptIn(ExperimentalNativeApi::class)
internal actual fun <T : Any> weakRef(referent: T): WeakRef<T> {
    val ref = WeakReference(referent)
    return WeakRef { ref.value }
}

@OptIn(NativeRuntimeApi::class)
internal actual fun collectGarbage() {
    GC.collect()
}
