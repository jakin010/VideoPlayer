# Fullscreen

Fullscreen changes where the video is shown, never what plays: item, position, play intent, volume, mute and speed are untouched, and the item is not prepared again.

## How it works

The library triggers fullscreen; the app decides where it is shown.

1. Turn fullscreen on for the players that offer it, in their configuration. Off by default, so no player offers a fullscreen that nothing shows:

   ```kotlin
   val controller = PlayerController(PlayerConfiguration(fullscreenEnabled = true), items = listOf(video))
   ```

2. Show `FullscreenVideoPlayer` while the player is fullscreen, wherever suits the app. Give it the whole screen, outside any padding for system bars:

   ```kotlin
   Box(Modifier.fillMaxSize()) {
       ScreenContent() // with VideoPlayer(controller)
       val state by controller.state.collectAsState()
       if (state.presentation == Presentation.Fullscreen) FullscreenVideoPlayer(controller)
   }
   ```

Then:

- `controller.enterFullscreen()`, the fullscreen button or swiping up on the video switches `state.presentation` to `Fullscreen`, and `PresentationChanged` fires. Another player of the same coordinator that was fullscreen returns inline.
- `controller.exitFullscreen()`, the exit button, swiping down or Back on Android switches it back to `Inline`. Releasing a fullscreen player switches it back too, so a released player never stays on screen.
- Without `fullscreenEnabled`, `enterFullscreen()` does nothing and logs a warning, `state.isFullscreenAvailable` is `false`, `FullscreenButton` hides itself and swiping up is left alone.

### Where to show it

- **Above one screen**, as in the example: the fullscreen player covers that screen's content.
- **For a whole coordinator**: one place shows whichever of its players is fullscreen, which suits feeds. The sample does this at the root of its UI for each coordinator:

  ```kotlin
  val player by coordinator.fullscreenPlayer.collectAsState()
  player?.let { FullscreenVideoPlayer(it) }
  ```

- **As a navigation destination or a dialog**: navigate on `PresentationChanged(to = Fullscreen)` and back on `PresentationChanged(to = Inline)`. Back inside `FullscreenVideoPlayer` calls `exitFullscreen()`, so the app only follows the presentation.

The app keeps the two in step. If it leaves a screen while a player there is fullscreen, it calls `exitFullscreen()`; otherwise the player stays `Fullscreen` with nothing showing it.

## What FullscreenVideoPlayer shows

- The video at its native aspect ratio, fitted to the space and centered, with black bars where the shapes differ. The inline surface's aspect ratio and content scale don't apply; returning inline restores them.
- The default controls with auto-hide: `PlayerControls` at the bottom and `FullscreenControls` in the top right, where the fullscreen button is an exit button with rotate-left and rotate-right buttons beside it (see [Turning the view](#turning-the-view)).
- The video gestures, including swipe down to leave.
- The system bars hidden, and Back leaving fullscreen. It takes every touch, so nothing beneath it reacts.
- Controls are kept clear of the notch and other cutouts, also when the view is turned.

It uses the [theme](ui-components.md#theme) provided where it is shown. Gestures and auto-hide are tuned with the same parameters as `VideoPlayer`: `hideControlsAfter`, `gestures`, `customGestures`, `colors`, `loading` and `error`. Each time a different player is shown, its controls, gestures and rotation start fresh.

### Swiping

- Swipe up on an inline video and release to enter fullscreen; swipe down in fullscreen to leave. The swipe must travel at least 48 dp and be mostly vertical. Releasing short of that does nothing.
- While swiping, a chevron in the swipe's direction fades in at the center of the video and is fully shown once releasing will act. Moving back before letting go fades it out again and cancels the swipe. See `SwipeIndicator` in [UI components](ui-components.md#gestures).
- Up only counts on a player with fullscreen enabled, so on other players a swipe up is left to whatever is behind the video. Swiping down on an inline video is never claimed.
- In a turned view, "down" is down for the video, not the screen: with the view turned 90 degrees, swiping from the screen's right edge toward the left leaves fullscreen.
- `gestures = VideoGestures(swipeToFullscreen = false)` turns it off on `VideoPlayer` (entering) or `FullscreenVideoPlayer` (leaving). Turn it off in scrolling feeds: a swipe up that starts on a video belongs to the player, so the feed wouldn't scroll. See [Feeds and the coordinator](feeds-and-coordinator.md#a-feed).

### Custom controls

`controls` replaces the whole overlay (controls, loading and error UI). The surface, gestures, auto-hide timing, rotation and Back handling stay:

```kotlin
FullscreenVideoPlayer(
    controller,
    controls = { visibility ->
        MyFullscreenControls(controller, visibility)
    },
)
```

The parts of the default overlay are public, so custom controls can reuse them. `FullscreenControls` is the top-right group, the counterpart of `PlayerControls` at the bottom:

```kotlin
Box(Modifier.fillMaxSize()) {
    PlayerControls(controller, Modifier.align(Alignment.BottomStart), visibility = visibility)
    FullscreenControls(controller, Modifier.align(AbsoluteAlignment.TopRight), visibility = visibility)
}
```

Inside `FullscreenVideoPlayer`, `FullscreenControls` gets the view's rotation from `LocalFullscreenViewRotation` and shows the rotate buttons. Custom rotate buttons read it the same way; it is `null` elsewhere or with rotation off:

```kotlin
val rotation = LocalFullscreenViewRotation.current
if (rotation != null) {
    RotateLeftButton(rotation, visibility = visibility)
    RotateRightButton(rotation, visibility = visibility)
}
```

`rotation.degrees` (0, 90, 180 or 270, clockwise) is readable state; `rotateLeft()` and `rotateRight()` turn it 90 degrees.

## Screen rotation

The screen's orientation follows the device; the library never forces or locks it. On Android, rotating recreates the activity: `FullscreenVideoPlayer` leaves composition and comes back, because the player's presentation is still `Fullscreen`, and it keeps playing. The controller must be app- or coordinator-owned for that, see [The player controller](player-controller.md#creating-a-controller).

## Turning the view

`FullscreenVideoPlayer` can turn the video and its controls in 90-degree steps inside the screen. The screen itself doesn't rotate, so this works while the user has locked the screen's rotation: a landscape video can fill a phone held sideways with rotation lock on.

```kotlin
FullscreenVideoPlayer(
    controller,
    rotation = FullscreenRotation(
        showButtons = true,   // rotate-left and rotate-right next to the exit button
        autoRotate = true,    // turn to fit the video's shape on entering
    ),
)
FullscreenVideoPlayer(controller, rotation = null) // never turned
```

Both options are on by default. With `rotation = null` the view is never turned and the buttons are gone.

### Auto-rotate

On entering fullscreen, the view turns so the video's long side follows the screen's long side:

| Screen | Video | Turned |
|---|---|---|
| Portrait | Landscape (16:9, 4:3, ...) | Yes |
| Landscape | Portrait (9:16, ...) | Yes |
| Portrait | Portrait | No, it already fits |
| Landscape | Landscape | No, it already fits |
| Any | Square (within 10 % of 1:1) | No |
| Any | Size not known yet | Not until the size arrives |

- The device's motion sensors pick the direction, so the video comes out upright for how the device is held: tilted with its top to the right, the view turns counterclockwise, otherwise clockwise. The sensors work regardless of rotation lock. Held upright, lying flat or without a sensor reading within half a second, it turns clockwise.
- When the screen itself rotates (rotation lock off) or the video's shape changes, for example on the next item, the choice is made again. Turning the device into landscape with rotation lock off therefore turns the view back upright, because the video now matches the screen.
- A turn the user made with the buttons is kept until the screen or the video's shape changes.
- Each time a player enters fullscreen it starts fresh.

### How it turns

The surface turns the video natively (`rotationDegrees`), fitted at its native aspect ratio in the turned frame, and turns its gestures with it. The controls are turned in Compose to the same angle: the scrubber runs along the video's bottom edge, the buttons stay in the video's top right, and taps, double-tap sides and swipes follow the video.

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

On Android, `FullscreenVideoPlayer` hides the bars itself; a swipe from the edge shows them briefly.

## Your own fullscreen screen

Instead of `FullscreenVideoPlayer`, you can show your own fullscreen view the same way, built from a `VideoPlayerSurface` and the public controls. The surface you show last renders the video, so the handoff needs no extra calls. You then hide the system bars and handle Back yourself.
