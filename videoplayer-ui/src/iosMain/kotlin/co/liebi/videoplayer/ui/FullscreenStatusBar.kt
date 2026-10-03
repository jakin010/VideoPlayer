package co.liebi.videoplayer.ui

/**
 * Whether a fullscreen player wants the status bar hidden (§12). Compose can't hide the iOS status bar
 * itself, so the view hosting the Compose UI applies it. In SwiftUI:
 *
 * ```
 * ComposeView()
 *     .ignoresSafeArea()
 *     .statusBarHidden(statusBarHidden) // a @State updated from observe { … }
 * ```
 *
 * A UIKit parent view controller returns [isHidden] from `prefersStatusBarHidden` and calls
 * `setNeedsStatusBarAppearanceUpdate()` from [observe].
 */
public object FullscreenStatusBar {
    private var requests = 0
    private val observers = mutableListOf<(Boolean) -> Unit>()

    /** `true` while a `FullscreenHost` shows a player. */
    public val isHidden: Boolean
        get() = requests > 0

    /** Calls [onChange] with the current value now and after every change. Returns a function that stops observing. */
    public fun observe(onChange: (Boolean) -> Unit): () -> Unit {
        observers += onChange
        onChange(isHidden)
        return { observers -= onChange }
    }

    internal fun acquire() {
        if (requests++ == 0) notifyObservers()
    }

    internal fun release() {
        if (requests > 0 && --requests == 0) notifyObservers()
    }

    private fun notifyObservers() {
        observers.toList().forEach { it(isHidden) }
    }
}
