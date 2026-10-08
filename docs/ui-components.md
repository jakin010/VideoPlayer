# UI components

`videoplayer-ui` is a convenience layer: everything it does, your own controls can do with the same controller calls. Each part is a separate composable, so you can use `VideoPlayer` as is or build a custom layout from the parts.

## VideoPlayer

The video with the default controls overlaid, auto-hide and all gestures wired:

```kotlin
VideoPlayer(
    controller = controller,
    aspectRatio = VideoAspectRatio.Native,      // follow the video's shape
    contentScale = VideoContentScale.Crop,      // Fit, Crop or Fill
    poster = { MyPoster() },
    transform = VideoTransform(zoom = 1.5f),    // pan, zoom and rotation, see Transform
    hideControlsAfter = 3.seconds,
    gestures = VideoGestures(),                 // each gesture can be turned off, see Gestures
    customGestures = Modifier,                  // the app's own gestures on the video
    loading = { LoadingIndicator() },           // slot
    error = { error, retry -> ErrorPanel(retry) }, // slot
)
```

It shows:

- the play/pause and mute buttons above a full-width scrubber at the bottom (`PlayerControls`),
- a fullscreen button in the top right (`FullscreenControls`) when the player has `fullscreenEnabled` (see [Fullscreen](fullscreen.md)),
- the seek and swipe feedback (`SeekIndicator`, `SwipeIndicator`),
- the loading spinner while preparing, buffering or seeking, after half a second so quick stalls don't flash,
- the error panel with a Retry button when the player fails.

## VideoPlayerSurface

The video and its [gestures](#gestures), from `videoplayer-core`. It fills the width it gets and takes its height from `aspectRatio`. Controls can be layered on top.

```kotlin
VideoPlayerSurface(
    controller,
    aspectRatio = VideoAspectRatio.Ratio16x9,  // or Fixed(ratio), Native, Ratio4x3, Ratio1x1, Ratio9x16
    contentScale = VideoContentScale.Crop,
    poster = { MyPoster() },
    posterDelay = 1.seconds,
    rotationDegrees = 0,                       // 0, 90, 180 or 270, clockwise
    transform = null,                          // pan, zoom and rotation, see Transform
    gestures = VideoGestures(),                // VideoGestures.None for a surface without gestures
    gestureState = rememberVideoGestureState(), // for feedback drawn on top
    onTap = { visibility.toggle() },           // what a single tap does
    customGestures = Modifier,                 // the app's own gestures
)
```

- `Native` follows the video size, or 16:9 until it is known. Aspect changes under 1 % (HLS quality switches) are ignored so the surface doesn't jump.
- The poster covers the area until the first frame. When switching items, the last frame stays on screen and the poster only appears if the next first frame takes longer than `posterDelay`, so quick switches go straight from video to video.
- `rotationDegrees` turns the video inside the area. With `Native`, the area takes the turned video's shape. The poster isn't turned; the gestures are, so they follow the video. `FullscreenVideoPlayer` uses it to turn the view, see [Fullscreen](fullscreen.md#turning-the-view).
- On Android it uses a `SurfaceView` (cheapest on battery), or a `TextureView` while turned, because a `SurfaceView` can't be rotated. On iOS it uses an `AVPlayerLayer` in a `UIKitView`.

## Transform

`transform` on `VideoPlayer` and `VideoPlayerSurface` pans, zooms and turns the video inside the player, for example to frame a detail. The player keeps its size and place, and controls and gestures are unaffected. It applies to the inline player only: fullscreen always shows the whole video.

```kotlin
VideoPlayer(
    controller,
    aspectRatio = VideoAspectRatio.Ratio1x1,
    transform = VideoTransform(panX = 0f, zoom = 1.5f, rotation = 10f),
)
```

Every value is relative, so one transform suits any player and video size:

| Value | Meaning |
|---|---|
| `panX`, `panY` | Where the video sits: 0 puts its left (`panX`) or top (`panY`) edge on the player's edge, 0.5 centers it, 1 puts its right or bottom edge on the player's edge, and values in between move it evenly. Out of bounds, values outside 0..1 move it further: -0.25 leaves a quarter of the video's width of background before its left edge, 1.25 after its right edge. |
| `zoom` | 1 is the size the content scale gives; 2 shows the video twice as large |
| `rotation` | Clockwise, in degrees |
| `allowOutOfBounds` | Off by default: the values are limited so the video keeps covering the player. On: every value applies as given. |

With `allowOutOfBounds` off, the transform never uncovers more of the player than the content scale does:

- Zoom stays at 1 or more.
- Pans stay within 0..1, so the video moves between its edges and never past them. A 16:9 video in a 1:1 player at 1x moves left and right, but its height exactly fills the player, so `panY` has no effect until you zoom in.
- Rotation stops before a corner comes into view. At 1x that leaves no room to turn; zooming in allows more, so a rotation that didn't fit applies once the zoom makes room.
- Along a side the video doesn't fill, such as the bars of `VideoContentScale.Fit`, panning moves it between the bars.

The transform is a plain value: hold it in state and change it as often as needed, for example from sliders; the sample's Player tab has them. Leave it `null` when there is no transform. On Android a transformed player draws into a `TextureView`, which costs a little more battery than the default `SurfaceView`.

## Building your own layout

```kotlin
val visibility = rememberControlsVisibility(controller)
val gestureState = rememberVideoGestureState()

Box {
    VideoPlayerSurface(controller, gestureState = gestureState, onTap = visibility::toggle)
    SeekIndicator(gestureState, Modifier.matchParentSize())
    SwipeIndicator(gestureState, Modifier.matchParentSize())
    PlayerControls(controller, Modifier.align(Alignment.BottomStart), visibility = visibility)
    FullscreenControls(controller, Modifier.align(AbsoluteAlignment.TopRight), visibility = visibility)
}
```

| Part | What it does |
|---|---|
| `PlayerControls` | Play/pause, mute and the scrubber, for the bottom |
| `FullscreenControls` | The fullscreen or exit button, with the rotate buttons in a turnable fullscreen view, for the top right |
| `FullscreenVideoPlayer` | A whole fullscreen player, for the app to show while a player is fullscreen. See [Fullscreen](fullscreen.md) |
| `PlayPauseButton` | Pause while play intent is set (also while buffering), play otherwise, replay in `Ended` |
| `MuteButton` | Toggles mute; unmuting restores the volume |
| `FullscreenButton` | Enters or exits fullscreen; hidden unless the player has `fullscreenEnabled` |
| `RotateLeftButton`, `RotateRightButton` | Turn the fullscreen view 90 degrees. Pass them `LocalFullscreenViewRotation.current` in custom fullscreen controls |
| `PlayerScrubber` | Played, buffered and remaining media; seeks by dragging |
| `SeekIndicator` | Feedback for double-tap seeks, such as "+30 s" on the tapped side |
| `SwipeIndicator` | Feedback while swiping into or out of fullscreen: a chevron that fades in as the finger travels |
| `LoadingIndicator`, `ErrorPanel` | The default loading and error content |

Colors and icons come from the [theme](#theme). Every control also takes `colors: PlayerControlsColors`, which wins over the theme for that control. Sizes and spacing are in `PlayerControlsDefaults`.

## Theme

`VideoPlayerTheme` holds the colors and icons of the whole player UI: every control, the seek and swipe feedback, loading and error, and the fullscreen player. Provide it once around the app content, including wherever it shows `FullscreenVideoPlayer`:

```kotlin
val playerTheme = VideoPlayerTheme(
    colors = PlayerControlsDefaults.colors(
        contentColor = Color.White,
        playedTrackColor = brandColor,
        thumbColor = brandColor,
    ),
    icons = VideoPlayerIcons().copy(
        play = VideoPlayerIcon(Res.drawable.my_play),             // a Compose resource
        pause = VideoPlayerIcon(MyIcons.Pause, 14.dp),             // an ImageVector
        enterFullscreen = VideoPlayerIcon(painterResource(Res.drawable.my_expand), 12.dp, 12.dp),
    ),
)

ProvideVideoPlayerTheme(playerTheme) {
    AppContent()
}
```

- **Switching themes**: pass a different theme, and everything showing restyles on the next frame, including a fullscreen player. Keep the theme in state, or derive it from the app's own theme, for example `if (isSystemInDarkTheme()) darkPlayerTheme else lightPlayerTheme`. Playback isn't affected.
- **Colors**: `PlayerControlsColors` has the icon color (`contentColor`), the circle behind buttons and feedback (`buttonContainerColor`), and the scrubber's played, buffered and remaining track and thumb colors. `PlayerControlsDefaults.colors(...)` starts from the defaults.
- **Icons**: `VideoPlayerIcons` has one `VideoPlayerIcon` per place: `play`, `pause`, `replay`, `soundOn`, `soundOff`, `enterFullscreen`, `exitFullscreen`, `rotateLeft`, `rotateRight`, `seekBack`, `seekForward`, `swipeUp` and `swipeDown`. Use `copy` to replace only some.
- **One icon**: a `DrawableResource`, an `ImageVector` or a `Painter`, with the `width` and `height` it is drawn at (12 dp square by default, the icon fitted inside and centered). Icons are drawn in `contentColor`; pass `tinted = false` to keep an icon's own colors. A `Painter` comes from composition (`painterResource`, `rememberVectorPainter`), so a theme with one is built inside a composable.
- **One player different**: nest another `ProvideVideoPlayerTheme` around that player, or pass `colors` to it.
- Themes compare by value, so creating an equal theme again doesn't restyle anything.

## Auto-hide

Controls can't know whether they sit over the video, so auto-hide is a state object you pass to the controls that should hide:

- `rememberControlsVisibility(controller, hideAfter)` returns a `ControlsVisibility` with `isVisible`, `show()`, `hide()`, `toggle()` and `onInteraction()`.
- Controls given it fade with it. Controls without it are always visible, which suits controls placed below the video.
- It hides `hideAfter` after the last interaction while playing. It stays visible while paused, ended, loading or in error, while scrubbing, and whenever TalkBack or VoiceOver is on. A hold doesn't force it visible.
- The default controls call `onInteraction()` on every tap and drag. Custom controls call it themselves.

## Gestures

The gestures live on `VideoPlayerSurface`, so every player has them: `VideoPlayer`, `FullscreenVideoPlayer` and custom layouts alike. `VideoGestures` turns each one on or off; all are on by default:

```kotlin
VideoPlayer(controller, gestures = VideoGestures(holdToPause = false, doubleTapSeek = DoubleTapSeek(step = 5.seconds)))
VideoPlayer(controller, gestures = VideoGestures.None) // no built-in gestures
```

| Gesture | Option | Effect |
|---|---|---|
| Tap | `tap` | Calls the surface's `onTap`; `VideoPlayer` toggles its controls. Acts after the double-tap timeout when double-tap seek is on. |
| Double tap left / right | `doubleTapSeek` (`null` turns it off) | Seeks one step back / forward. Each further tap on the same side adds a step. |
| Double tap in the middle 20 % | | Nothing |
| Press and hold | `holdToPause` | Pauses while held, resumes on release. Never shows the controls. |
| Swipe up and release | `swipeToFullscreen` | Enters fullscreen, when the player has `fullscreenEnabled` |
| Swipe down and release, in fullscreen | `swipeToFullscreen` | Leaves fullscreen |

- Hold starts after the platform long-press timeout without moving past touch slop, so scrolling a feed never triggers it. It does nothing on a paused, ended or failed player.
- Double-tap seek never changes play intent and does nothing on media that can't seek.
- A swipe must be mostly vertical and travel at least 48 dp before release. Once it moves past touch slop in the direction that counts, the player claims it, so a scrolling parent doesn't scroll. Other drags are left to the parent. Turn swiping off with `swipeToFullscreen = false` in scrolling feeds. See [Fullscreen](fullscreen.md#swiping).
- Controls drawn over the surface take their own touches, so tapping a button never toggles the controls.
- Gestures nothing handles leave touches alone: a surface without `onTap` and without double-tap seek doesn't claim taps, and with `VideoGestures.None` it takes no touches at all, so a `clickable` around it still works.
- In a turned surface (`rotationDegrees`), the gestures turn with the video: the video's right side seeks forward, and down is down for the video.
- While a swipe is claimed, `SwipeIndicator` shows a chevron in its direction in the center. It fades in, grows and drifts to the center as the finger travels, is fully shown once releasing will act, and fades out on release.
- `VideoGestureState` holds `seekFeedback`, `swipeFeedback` (direction and progress from 0 to 1) and `isHolding`, readable state for custom feedback. Pass the same state to the surface and to the feedback.

### Your own gestures

`customGestures` on `VideoPlayer`, `FullscreenVideoPlayer` and `VideoPlayerSurface` adds the app's gestures to the video, as gesture modifiers:

```kotlin
VideoPlayer(
    controller,
    customGestures = Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(onDragEnd = { playNext() }) { _, _ -> }
    },
)
```

- They see each touch before the built-in gestures. A touch they consume is ignored by the built-in gestures, so an app can take a gesture over, for example its own double tap. Touches they leave alone still reach the built-in gestures.
- In a turned view they turn with the video too, so their positions are in the video's frame.
- Key `pointerInput` the way Compose expects, for example on `Unit` or on the values the gesture reads.

## Scrubbing

- The handle's touch target is at least 48 dp, although the scrubber is only as tall as its thumb.
- A drag starting on the handle follows the finger. A drag starting elsewhere moves the position by the drag distance; the touch point never becomes the position. A tap never seeks.
- While dragging, the target is shown and playback continues. On release, one `seekTo` runs and the target stays on screen until the seek lands, so it never snaps back.
- Disabled when the media can't seek, including live streams.
- The thumb moves every frame between progress updates, so short videos scrub smoothly.

## Right-to-left and accessibility

- Media controls never mirror: left is always back, right always forward, in every language. Only text follows the layout direction.
- Every control exposes a role, a label and its state (Play or Pause, Mute or Unmute, Fullscreen or Exit fullscreen, Rotate left, Rotate right, Retry).
- The scrubber reads like "Video position, 1 minute 24 seconds of 3 minutes 12 seconds". TalkBack and VoiceOver adjustments seek about 10 s per step.
- Loading and error changes are announced.

## Strings and icons

- Strings are Compose resources in `videoplayer-ui/src/commonMain/composeResources/values/strings.xml` (English), translated into Dutch, French, Spanish, German, Italian, Chinese (Simplified), Hindi, Arabic, Portuguese (Brazil), Bengali, Russian, Urdu, Indonesian, Japanese, Turkish, Polish, Icelandic, Norwegian, Swedish, Finnish and Amharic. The device language picks the translation; other languages get English.
- To add a language, add `values-<language>/strings.xml` with every string and plural of the English file, using that language's plural forms (`one`, `few`, `many` and so on, always including `other`). `TranslationsTest` fails when a translation misses a string or a placeholder.
- Indonesian and Norwegian have two folders with the same content (`id` and `in`, `nb` and `no`), because platforms report different codes for them.
- On iOS, the app must declare its languages in `CFBundleLocalizations` for right-to-left layouts to apply, see [Getting started](getting-started.md#ios).
- The translations haven't been reviewed by native speakers yet.
- For different wording without changing the library, pass your own `loading` and `error` content.
- The default icons are vector drawables in `composeResources/drawable/`, supplied with the project. Apps replace them through the [theme](#theme). Inside the library, don't add icon libraries or make icons; ask for a new icon when one is missing.
