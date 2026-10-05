# Fullscreen

Fullscreen changes where the video is shown, never what plays: item, position, play intent, volume, mute and speed are untouched, and the item is not prepared again.

## Setup

Place one `FullscreenHost` per coordinator at the root of your UI, after the app content and outside any padding for system bars:

```kotlin
Box(Modifier.fillMaxSize()) {
    AppContent()
    FullscreenHost()                     // players of PlayerCoordinator.Default
    FullscreenHost(feedCoordinator)      // players of another coordinator, if you have one
}
```

Then:

- `controller.enterFullscreen()` shows that player in the host, above all app content, with the system bars hidden. Another player that was fullscreen returns inline.
- `controller.exitFullscreen()`, the exit button or Back on Android returns it inline.
- `state.presentation` becomes `Fullscreen` and `PresentationChanged` fires.
- Without a host, `enterFullscreen()` does nothing and logs a warning, `state.isFullscreenAvailable` is `false`, and `FullscreenButton` hides itself.

## What the host shows

- The video at its native aspect ratio, fitted to the screen and centered, with black bars where the shapes differ. The inline surface's aspect ratio and content scale don't apply; returning inline restores them.
- The default controls with auto-hide, double-tap seek and hold-to-pause. The fullscreen button in the top right becomes an exit button.
- Controls are kept clear of the notch and other cutouts.

Gestures and auto-hide can be tuned with the same parameters as `VideoPlayer`: `hideControlsAfter`, `tapTogglesControls`, `holdToPause`, `doubleTapSeek`, `colors`, `loading` and `error`.

### Custom controls

`controls` replaces the whole overlay (controls, loading and error UI). The surface, gestures, auto-hide timing and Back handling stay. The slot gets the fullscreen player, so controls can differ per player:

```kotlin
FullscreenHost(
    controls = { controller, visibility ->
        MyFullscreenControls(controller, visibility)
    },
)
```

## Rotation

Orientation follows the device; the library never forces a rotation. On Android, rotating recreates the activity: the host leaves composition and comes back, and the player stays fullscreen and keeps playing. The controller must be app- or coordinator-owned for that, see [The player controller](player-controller.md#creating-a-controller).

## iOS status bar

Compose can't hide the iOS status bar itself, so the view hosting your Compose UI applies `FullscreenStatusBar` from `videoplayer-ui`.

SwiftUI, as in the sample's `ContentView.swift`. Expose a small helper from your shared Kotlin code:

```kotlin
// iosMain
fun observeFullscreenStatusBar(onChange: (Boolean) -> Unit) {
    FullscreenStatusBar.observe(onChange)
}
```

```swift
final class FullscreenModel: ObservableObject {
    @Published var isFullscreen = false

    init() {
        MainViewControllerKt.observeFullscreenStatusBar { [weak self] hidden in
            self?.isFullscreen = hidden.boolValue
        }
    }
}

struct ContentView: View {
    @StateObject private var fullscreen = FullscreenModel()

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .statusBarHidden(fullscreen.isFullscreen)
            .persistentSystemOverlays(fullscreen.isFullscreen ? .hidden : .automatic)
    }
}
```

UIKit: a parent view controller returns `FullscreenStatusBar.isHidden` from `prefersStatusBarHidden` and calls `setNeedsStatusBarAppearanceUpdate()` from `observe`.

On Android the host hides the bars itself; a swipe from the edge shows them briefly.

## Your own fullscreen screen

You can skip the host and build a fullscreen screen yourself by placing a `VideoPlayerSurface` in it. The surface you show last renders the video, so the handoff needs no extra calls. `presentation` stays `Inline` in that case, and you handle system bars and Back yourself.
