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
    hideControlsAfter = 3.seconds,
    tapTogglesControls = true,
    holdToPause = true,
    doubleTapSeek = DoubleTapSeek(),            // null turns it off
    loading = { LoadingIndicator() },           // slot
    error = { error, retry -> ErrorPanel(retry) }, // slot
)
```

It shows:

- the play/pause and mute buttons above a full-width scrubber at the bottom (`PlayerControls`),
- a fullscreen button in the top right, once a `FullscreenHost` is placed (see [Fullscreen](fullscreen.md)),
- the loading spinner while preparing, buffering or seeking, after half a second so quick stalls don't flash,
- the error panel with a Retry button when the player fails.

## VideoPlayerSurface

The video alone, from `videoplayer-core`. It fills the width it gets and takes its height from `aspectRatio`. It handles no input, so gestures and controls can be layered on top.

```kotlin
VideoPlayerSurface(
    controller,
    aspectRatio = VideoAspectRatio.Ratio16x9,  // or Fixed(ratio), Native, Ratio4x3, Ratio1x1, Ratio9x16
    contentScale = VideoContentScale.Crop,
    poster = { MyPoster() },
    posterDelay = 1.seconds,
)
```

- `Native` follows the video size, or 16:9 until it is known. Aspect changes under 1 % (HLS quality switches) are ignored so the surface doesn't jump.
- The poster covers the area until the first frame. When switching items, the last frame stays on screen and the poster only appears if the next first frame takes longer than `posterDelay`, so quick switches go straight from video to video.
- On Android it uses a `SurfaceView` (cheapest on battery); on iOS an `AVPlayerLayer` in a `UIKitView`.

## Building your own layout

```kotlin
val visibility = rememberControlsVisibility(controller)
val gestures = rememberVideoGestures(controller, visibility = visibility)

Box(Modifier.videoGestures(gestures)) {
    VideoPlayerSurface(controller)
    SeekIndicator(gestures, Modifier.matchParentSize())
    PlayerControls(controller, Modifier.align(Alignment.BottomStart), visibility = visibility)
}
```

| Part | What it does |
|---|---|
| `PlayerControls` | Play/pause, mute and the scrubber; nothing else |
| `PlayPauseButton` | Pause while play intent is set (also while buffering), play otherwise, replay in `Ended` |
| `MuteButton` | Toggles mute; unmuting restores the volume |
| `FullscreenButton` | Enters or exits fullscreen; hidden without a host |
| `PlayerScrubber` | Played, buffered and remaining media; seeks by dragging |
| `SeekIndicator` | Feedback for double-tap seeks, such as "+30 s" on the tapped side |
| `LoadingIndicator`, `ErrorPanel` | The default loading and error content |

Every control takes `colors: PlayerControlsColors`. Change them with `PlayerControlsDefaults.colors(contentColor = …, playedTrackColor = …)`. Sizes and spacing are in `PlayerControlsDefaults`.

## Auto-hide

Controls can't know whether they sit over the video, so auto-hide is a state object you pass to the controls that should hide:

- `rememberControlsVisibility(controller, hideAfter)` returns a `ControlsVisibility` with `isVisible`, `show()`, `hide()`, `toggle()` and `onInteraction()`.
- Controls given it fade with it. Controls without it are always visible, which suits controls placed below the video.
- It hides `hideAfter` after the last interaction while playing. It stays visible while paused, ended, loading or in error, while scrubbing, and whenever TalkBack or VoiceOver is on. A hold doesn't force it visible.
- The default controls call `onInteraction()` on every tap and drag. Custom controls call it themselves.

## Gestures

`rememberVideoGestures(controller, visibility, holdToPause, doubleTapSeek)` plus `Modifier.videoGestures(gestures)` on the surface or any overlay above it:

| Gesture | Effect |
|---|---|
| Tap | Toggles the controls, if a visibility state is passed. Acts after the double-tap timeout when double-tap seek is on. |
| Double tap left / right | Seeks one step back / forward. Each further tap on the same side adds a step. |
| Double tap in the middle 20 % | Nothing |
| Press and hold | Pauses while held, resumes on release. Never shows the controls. |

- Hold starts after the platform long-press timeout without moving past touch slop, so scrolling a feed never triggers it. It does nothing on a paused, ended or failed player.
- Double-tap seek never changes play intent and does nothing on media that can't seek.
- Touches a child control consumed are ignored, so tapping a button never toggles the controls.
- `gestures.seekFeedback` and `gestures.isHolding` are readable state for custom feedback.

## Scrubbing

- The handle's touch target is at least 48 dp, although the scrubber is only as tall as its thumb.
- A drag starting on the handle follows the finger. A drag starting elsewhere moves the position by the drag distance; the touch point never becomes the position. A tap never seeks.
- While dragging, the target is shown and playback continues. On release, one `seekTo` runs and the target stays on screen until the seek lands, so it never snaps back.
- Disabled when the media can't seek, including live streams.
- The thumb moves every frame between progress updates, so short videos scrub smoothly.

## Right-to-left and accessibility

- Media controls never mirror: left is always back, right always forward, in every language. Only text follows the layout direction.
- Every control exposes a role, a label and its state (Play or Pause, Mute or Unmute, Fullscreen or Exit fullscreen, Retry).
- The scrubber reads like "Video position, 1 minute 24 seconds of 3 minutes 12 seconds". TalkBack and VoiceOver adjustments seek about 10 s per step.
- Loading and error changes are announced.

## Strings and icons

- Strings are Compose resources in `videoplayer-ui/src/commonMain/composeResources/values/strings.xml` (English), translated into Dutch, French, Spanish, German, Italian, Chinese (Simplified), Hindi, Arabic, Portuguese (Brazil), Bengali, Russian, Urdu, Indonesian, Japanese, Turkish, Polish, Icelandic, Norwegian, Swedish, Finnish and Amharic. The device language picks the translation; other languages get English.
- To add a language, add `values-<language>/strings.xml` with every string and plural of the English file, using that language's plural forms (`one`, `few`, `many` and so on, always including `other`). `TranslationsTest` fails when a translation misses a string or a placeholder.
- Indonesian and Norwegian have two folders with the same content (`id` and `in`, `nb` and `no`), because platforms report different codes for them.
- On iOS, the app must declare its languages in `CFBundleLocalizations` for right-to-left layouts to apply, see [Getting started](getting-started.md#ios).
- The translations haven't been reviewed by native speakers yet.
- For different wording without changing the library, pass your own `loading` and `error` content.
- Icons are vector drawables in `composeResources/drawable/`, supplied with the project. Don't add icon libraries; ask for a new icon when one is missing.
