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
- `registerFullscreenHost` counts hosts and keeps the fullscreen player while the count drops to zero, because on Android rotation the old host leaves before the new one arrives.

## rememberPlayerController

**Where:** the bottom of `PlayerCoordinator.kt`.

It resolves the controller in composition, keyed on `(key, coordinator, generation)`, and an effect only bumps `generation` when the controller is released while still composed. An earlier version resolved the replacement inside the effect, which ran against a stale coordinator after the app swapped coordinators and created stray players. Keep the resolving in composition.

## UI

- **Scrubber smoothing** (`rememberSmoothPosition` in `PlayerScrubber.kt`): progress arrives every 250 ms, so the thumb is extrapolated every frame from the latest update and the playback speed, and re-anchored on each update. Changes under half a pixel are skipped so long videos don't redraw every frame.
- **Poster delay** (`VideoPlayerSurface`): once video has been shown, the poster only appears if the next first frame takes longer than `posterDelay`. Meanwhile the surface keeps the previous frame (`keepPreviousFrame`), which needs platform support, see the platform notes.
- **Stable video size**: aspect changes under 1 % are ignored. HLS renditions differ slightly (512x288 and 848x480 aren't quite the same shape), and resizing the native surface on every switch showed stretched frames.
- **Touch targets**: buttons are 28 dp but take touches in a 48 dp area through `expandedPointerInput`, without taking layout space. Video gestures ignore touches a child consumed, which is how a tap on a button doesn't toggle the controls.
- **Fullscreen button placement**: it sits in the top right, outside `PlayerControls`. `PlayerControls` holds only play/pause, mute and the scrubber, by design.

## Kotlin and Compose toolchain

- **Compose resources formatting**: only positional placeholders such as `%1$d` and `%1$s` are substituted. `%d` is printed literally.
- **Compose UI tests**: anything animating every frame (the scrubber while playing) keeps the test from going idle. Use `mainClock.autoAdvance = false` and advance by hand.
- **`expect class`** is still Beta and warns. Prefer an `expect fun` returning a `fun interface`, as `weakRef()` in the sample does.
- **Kotlin/Native keeps dead locals in suspended coroutine frames**; the JVM clears them. In leak tests, create each object inside its own suspend function, or the last one stays reachable while the caller waits.
- **`OptIn` clash on Android**: Media3 files import `androidx.annotation.OptIn` for `UnstableApi`. Kotlin opt-ins in those files need the qualified `@kotlin.OptIn(...)`.
