package co.liebi.videoplayer.sample.checks

/** Lets a released player be checked for leaks without keeping it alive. */
internal fun interface WeakRef<T : Any> {
    fun get(): T?
}

internal expect fun <T : Any> weakRef(referent: T): WeakRef<T>

/** Runs a full garbage collection, as far as the platform allows. */
internal expect fun collectGarbage()

/** Hooks for the app shell: launch options and a leak watcher (LeakCanary on Android debug builds). */
object ChecksHooks {
    /**
     * Set at launch to run checks automatically: `parity`, `parity:<name>,<name>` for some scenarios, `cycles`,
     * `feed`, `leaks` (cycles and feed) or `all`.
     */
    var autoRun: String? = null

    /** Called with every player the checks release; it must become unreachable. */
    var watchReleased: (player: Any, description: String) -> Unit = { _, _ -> }
}

/** One machine-readable line per result, so runs on both platforms can be collected and compared. */
internal fun report(line: String) = println("LVP-CHECK|$line")
