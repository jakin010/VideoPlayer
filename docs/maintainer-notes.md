# Maintainer notes

Code that looks simple but isn't, and why it is written the way it is. Platform-specific code is covered in [Platform notes](platform-notes.md).

## Re-entrancy and reconcile

**Where:** `DefaultPlayerController.operation()` and `reconcile()`.

Engine callbacks (Media3 calls listeners synchronously) and app observers can call into the controller while it is in the middle of a change. App coroutines on `Dispatchers.Main.immediate` even resume inside the `_state.update` that woke them, and can call `pause()` right there.

- `operation` queues calls made while another operation runs.
- `reconcile()` runs after the first block and after each queued block. The order matters: a queued `pause()` must run after the `PlaybackStarted` of the playback the observer just saw. Running queued blocks first and reconciling once at the end lost that event; the parity suite found it on both platforms.
- `progress` is published before `state` names a new item, for the same reason: an observer of the item change must not read the old item's position.

The tests in the "Re-entrant observers" region of `DefaultPlayerControllerTest` reproduce both cases with `UnconfinedTestDispatcher`. Keep them passing.

## Deriving events

Events come from state transitions, never directly from engine callbacks. The rules that are easy to break:

- `PlaybackStarted` fires once per play intent (`startedForIntent`), when `isPlaying` first becomes true. A stall ending doesn't fire it again. `isFirstStart` comes from `hasStartedItem`, which survives suspension and switching back to a kept item, so those report `false`.
- `BufferingStarted` only fires for a stall while playing: not during the initial load (`hasBeenReady` is false) and not during a seek (`isSeeking`).
- A user pause is never replaced by an automatic reason (`clearPlayIntent`). A hold, an interruption or backgrounding records whether the player was playing (`isPlayingOrHeld`), so ending it only resumes when it should.
- `FirstFrameRendered` has no fixed order relative to `PlaybackStarted`. Don't add logic that assumes one.

When changing any of these, run the parity suite on both platforms, see [Testing](testing.md#checks-on-the-real-engines).

## Kept items for instant switch-back

**Where:** `keepCurrentItemPrepared`, `takePreparedItem`, `activatePrepared` and the `PreparedItem` listener.

On `selectItem`, a settled item's engine is parked instead of unloaded, and the controller creates a new engine for the next item. `PreparedItem` keeps listening to the parked engine, so its status, timeline and video size stay current while parked. Switching back moves the engine back in without preparing again.

Things to keep in mind:

- Only settled items are kept: ready (or stalled after being ready), with no seek in flight.
- A kept item whose source changed (`setItems`, `updateSource`) is released, never reused.
- A kept item that fails is dropped quietly; selecting it again prepares from scratch.
- Kept items count against the coordinator's player cap and are the first thing it frees.

## The coordinator

**Where:** `PlayerCoordinator.kt`.

- Controllers register in their `init`, at the very end, once every property is initialized. Registering creates an event from the controller's state, so moving `coordinator.register(this)` earlier hands the coordinator a half-initialized controller.
- A coordinator-owned controller that never gets a surface still suspends after a second and is released after retention. Without that, controllers created by an abandoned composition (such as a cancelled lazy-list prefetch) would live forever.
- The cap counts kept items too (`nativePlayerCount`). `ensureCapacity` runs before an engine is created, never after.
- `fullscreenPlayer` only tracks which player is fullscreen, so only one per coordinator is. Showing it is the app's job; the presentation lives in the controller, so it survives the app's fullscreen view leaving and coming back on Android rotation.

## rememberPlayerController

**Where:** the bottom of `PlayerCoordinator.kt`.

It resolves the controller in composition, keyed on `(key, coordinator, generation)`, and an effect only bumps `generation` when the controller is released while still composed. An earlier version resolved the replacement inside the effect, which ran against a stale coordinator after the app swapped coordinators and created stray players. Keep the resolving in composition.

## UI

- **Scrubber smoothing** (`rememberSmoothPosition` in `PlayerScrubber.kt`): progress arrives every 250 ms, so the thumb is extrapolated every frame from the latest update and the playback speed, and re-anchored on each update. Changes under half a pixel are skipped so long videos don't redraw every frame.
- **Poster delay** (`VideoPlayerSurface`): once video has been shown, the poster only appears if the next first frame takes longer than `posterDelay`. Meanwhile the surface keeps the previous frame (`keepPreviousFrame`), which needs platform support, see the platform notes.
- **Stable video size**: aspect changes under 1 % are ignored. HLS renditions differ slightly (512x288 and 848x480 aren't quite the same shape), and resizing the native surface on every switch showed stretched frames.
- **Touch targets**: buttons are 28 dp but take touches in a 48 dp area through `expandedPointerInput`, without taking layout space. The controls are drawn above the surface as its siblings, so a touch on a button never reaches the surface's gestures: Compose sends a touch only to the topmost sibling that takes it.
- **Fullscreen button placement**: it sits in the top right, outside `PlayerControls`. `PlayerControls` holds only play/pause, mute and the scrubber, by design. In fullscreen, the rotate buttons sit to its left in a row forced left-to-right, so their order doesn't mirror.

## Theme

**Where:** `VideoPlayerTheme.kt`.

- `LocalVideoPlayerTheme` is a `staticCompositionLocalOf`: themes change rarely, and a change restyles everything under the provider anyway. Every `colors` parameter defaults to `LocalVideoPlayerTheme.current.colors`, so an explicit argument still wins.
- Icons are read inside each control, not passed as parameters, so the theme is the one place to change them.
- `VideoPlayerIcon` implements `equals` by value (drawable or vector or painter, size, tint), so an app creating an equal theme on each recomposition doesn't restyle anything. The default play icon has an internal `offsetX` for optical centering; it isn't public API.
- `compose.components.resources` is an `api` dependency, because `VideoPlayerIcon` takes a `DrawableResource`.
- Default icons come from the user. Don't draw or convert icons in this repo; ask for them.

## Video gestures

**Where:** `videoplayer-core/src/commonMain/.../VideoGestures.kt` and the gesture layer at the end of `VideoPlayerSurface`.

- The gestures are a transparent box above the video and poster, inside the surface. Core has no idea of controls, so a single tap only calls `onTap`; `VideoPlayer` passes `visibility::toggle`, and `KeepControlsWhileSeeking` turns each double-tap seek into `onInteraction()` by watching `seekFeedback`.
- The gesture box is laid out with the `turned` layout modifier (`internal/Turned.kt`): measured with width and height swapped and placed with a rotated layer. Pointer positions then arrive in the video's frame, so seek sides and swipe directions need no mapping. It is the same node at every angle, so turning the view doesn't restart the gesture detector. The Android `TextureView` is turned with it too.
- Gestures nothing handles stay out of the way: without an `onTap`, taps aren't claimed, and with every built-in gesture off there is no detector at all. Otherwise a parent's `clickable` around a bare surface would never fire.
- `customGestures` is applied after the built-in `pointerInput` in the modifier chain, which makes it the inner node: in the main pass it sees each event first. The built-in detector bails out on consumed changes, which is what lets the app take a gesture over.
- `onTap` goes through `rememberUpdatedState`, so a new lambda on each recomposition doesn't restart the pointer input.

## Swipe to fullscreen

**Where:** `awaitTapOrHold` and `completeSwipe` in `VideoGestures.kt`.

- The allowed direction comes from the controller's state at touch-down: down in fullscreen, up when fullscreen is enabled, none otherwise. A drag in any other direction stays unconsumed, so a scrolling parent still gets it, exactly as before swipes existed.
- A swipe is claimed at touch slop, by consuming the change, only when it is mostly vertical and in the allowed direction. Claiming later would let a scrolling parent take the drag first.
- `completeSwipe` reads `changedToUp()` before consuming the change. `changedToUp()` ignores consumed changes, so consuming first made every swipe look like it never ended. The swipe UI tests catch this.
- The decision uses the vertical distance at release, so moving back before letting go cancels the swipe. A second finger cancels it too.
- The swipe has to pass touch slop before the long-press timeout; a finger held still first becomes a hold, as before.
- In a turned view, the gesture box is turned with the video, so "down" means down for the video without any mapping.
- `swipeFeedback` is set from the claim on and cleared in a `finally`, so it can't stay behind when the gesture is cancelled. `SwipeIndicator` eases the progress over 150 ms and keeps the last direction while fading out.

## Video transform

**Where:** `VideoTransform.kt` and `internal/TransformMath.kt`; applied in both `PlatformVideoSurface` actuals.

- `resolveTransform` turns the relative values into a scale, a rotation and a translation, in the platform's units (pixels on Android, points on iOS). It takes the surface's turn too, so a turned video pans along its turned shape. Both platforms call it with their real area size, Android in the graphics layer's draw block and iOS when placing the layer, so the result never lags a frame behind the layout.
- Everything is computed from a focus point: the point of the video, as shown by the content scale, that ends up at the area's center. The translation is minus the rotated, scaled offset of that point from the center. The pan aligns the video along each side: with `shown = span / zoomed length` and `start` the point of the video at the span's start, `start = pan · (1 − shown)` for pans in 0..1, so 0 puts the video's start on the area's start, 1 its end on the area's end and 0.5 centers it, for videos longer or shorter than the span alike. Out of bounds, a pan below 0 is the start itself (the video's start moves into the area by that much of its length) and one above 1 continues from `1 − shown` the same way. `focus = start + shown / 2`.
- Bounds per side of the video: the area's corners, seen in the turned video's frame, span `w·|cos| + h·|sin|` along the video's width and `w·|sin| + h·|cos|` along its height. A side the zoomed video covers unturned must keep covering that span; that limits the rotation (bisection from 0 toward the requested angle). Within bounds the pan is clamped to 0..1, which keeps each covered side covered at that rotation.
- The tolerance is a ten-thousandth of the area: enough for float rounding where the video exactly spans the area, too small to leave a visible sliver. A fixed half pixel showed hairlines of background at the corners.

## Turning the fullscreen view

**Where:** `FullscreenContent` in `FullscreenVideoPlayer.kt`, `FullscreenRotation.kt`, and the platform surfaces.

- Native video views don't follow Compose's graphics layers (a `SurfaceView` and a `UIKitView` both draw outside them), so the video is turned by the platform through `VideoPlayerSurface(rotationDegrees)`, which turns its gesture box too. The surface covers the whole screen with a fixed ratio equal to the screen's and `Fit`, so the gestures cover the black bars as well. The controls and feedback are a Compose box sized to the turned frame with `requiredSize` and turned with `graphicsLayer { rotationZ }`. Both use the same `degrees`, so they can't disagree.
- Safe-drawing insets are absolute screen edges. `turnedSafeDrawing` maps them onto the turned box's edges, so the controls stay clear of the notch whichever way the view is turned.
- The first turn is computed in `remember`, without the sensor, so entering fullscreen doesn't show one unturned frame. `AutoRotate` then waits up to 0.5 s for the first tilt reading and only applies it when the user hasn't pressed a rotate button meanwhile.
- `AutoRotate` is keyed on the screen's shape and the video's shape, not on the tilt, so tilting the device later doesn't fight the user's buttons. The shape is kept while no video size is known, so switching items doesn't turn the view back and forth.
- `rotation = null` forces `degrees` to 0 even when a hoisted `viewRotation` is turned.

## Kotlin and Compose toolchain

- **Compose resources formatting**: only positional placeholders such as `%1$d` and `%1$s` are substituted. `%d` is printed literally.
- **Compose UI tests**: anything animating every frame (the scrubber while playing) keeps the test from going idle. Use `mainClock.autoAdvance = false` and advance by hand.
- **`expect class`** is still Beta and warns. Prefer an `expect fun` returning a `fun interface`, as `weakRef()` in the sample does.
- **Kotlin/Native keeps dead locals in suspended coroutine frames**; the JVM clears them. In leak tests, create each object inside its own suspend function, or the last one stays reachable while the caller waits.
- **`OptIn` clash on Android**: Media3 files import `androidx.annotation.OptIn` for `UnstableApi`. Kotlin opt-ins in those files need the qualified `@kotlin.OptIn(...)`.
