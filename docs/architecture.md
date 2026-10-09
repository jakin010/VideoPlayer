# Architecture

## Modules

```
videoplayer-ui ──► videoplayer-core ◄── videoplayer-test
       ▲                  ▲
       └── videoplayer-sample
```

| Module | Contents |
|---|---|
| `videoplayer-core` | Public API, the common state machine, the coordinator, `VideoPlayerSurface` and the two platform engines |
| `videoplayer-ui` | Default controls, auto-hide, feedback, `VideoPlayer`, `FullscreenVideoPlayer`, the theme, strings and icons |
| `videoplayer-test` | `FakePlayerController` |
| `videoplayer-sample` | Android and iOS app: Player, Coordinator and Checks tabs |
| `build-logic` | The `liebi.kmp.library` convention plugin shared by the library modules |

## Layers inside core

```
PlayerController (public interface)
      │
DefaultPlayerController ── all behavior, common code
      │   status, play intent, pause reasons, events, retries,
      │   position memory, prepared items, lifecycle
      │
PlaybackEngine (internal interface) ── raw facts only
      ├── ExoPlaybackEngine   (androidMain, Media3)
      └── AVPlaybackEngine    (iosMain, AVFoundation)

PlayerCoordinator ── everything that spans players
      └── SystemIntegration  (audio session or focus, interruptions, background)
```

The rule: platform code reports facts ("buffering", "ready", "ended", "first frame", "error X"), and common code decides what they mean. This is why both platforms emit the same events for the same scenario, and why most behavior is tested once against `FakePlaybackEngine`.

## DefaultPlayerController

`videoplayer-core/src/commonMain/kotlin/co/liebi/videoplayer/core/internal/DefaultPlayerController.kt`

- Holds `_state`, `_progress` and `_events`, and the current engine as a `StateFlow` so surfaces can follow it.
- Every command and every engine callback runs through `operation {}`. Engines and observers can call back synchronously, so `operation` serializes re-entrant calls: a call made while another operation runs is queued and runs afterwards.
- After each block, `reconcile()` applies the derived effects: the engine's play intent, `PlaybackStarted`, the progress ticker, progress publishing and the coordinator's audio vote. Queued calls run only after the current change is reconciled. See [Maintainer notes](maintainer-notes.md#re-entrancy-and-reconcile).
- Commands sent after `release()` are ignored and logged.

## PlaybackEngine

`internal/PlaybackEngine.kt` is the contract. An engine loads one item at a time, reports status (`Idle`, `Buffering`, `Ready`, `Ended`), the timeline, seek completion, first frame, video size and errors, and supports parking: keeping a prepared item paused without downloading more, for instant switch-back.

Engines apply the start gating from `BufferingConfig` themselves:

- **Media3** maps the thresholds onto `DefaultLoadControl`, so ExoPlayer gates natively. `ParkableLoadControl` wraps it to stop loading while parked.
- **AVFoundation** has no equivalent. The engine turns off `automaticallyWaitsToMinimizeStalling` and only sets a rate once `BufferGate` is satisfied, based on the item's loaded time ranges.

## Surfaces

`VideoPlayerSurface` (common) attaches a token to the controller while composed. The controller tracks surfaces and makes the most recently attached one active. `PlatformVideoSurface` draws the engine's output:

- Android: Media3's `PlayerSurface` with a `SurfaceView`, resized with `resizeWithContentScale`. A turned or transformed video draws into a `TextureView` instead, because a `SurfaceView` can't be rotated.
- iOS: the engine owns one `AVPlayerLayer`, which moves into whichever `UIKitView` is active. The previous layer stays underneath until the new one has a frame, so video-to-video switches don't flash. Turns and transforms are the layer's affine transform.

A `VideoTransform` (pan, zoom, rotation) is resolved by shared math in `internal/TransformMath.kt`, so both platforms place the video identically; each applies the result to its own view.

Because the layer or player moves between surfaces, a handoff (scrolling, fullscreen) never prepares the item again.

## Coordinator

`PlayerCoordinator.kt` keeps the registry, owned controllers by key, least-recently-used order for the player cap, the fullscreen player, and the audible vote. Controllers call it at fixed points: registration, play intent (single active player), before creating an engine (the cap), after each reconcile (audio) and on release.

`SystemIntegration` is the platform half. Each coordinator has one, which votes on app-wide audio: `SystemIntegration.android.kt` shares one audio focus request, the headphones receiver and the process lifecycle observer between all coordinators; `SystemIntegration.ios.kt` shares the audio session and the interruption, route-change and app lifecycle notifications. Both hold coordinators weakly.

## UI module

`VideoPlayer` and `FullscreenVideoPlayer` share one overlay (`DefaultOverlay`): loading and error in the center, `PlayerControls` at the bottom and `FullscreenControls` in the top right, with the rotate buttons in fullscreen. Auto-hide (`ControlsVisibility`) and the gesture feedback (`VideoGestureState`) are hoisted state objects, so they work on any layout.

The gestures themselves are in core, on `VideoPlayerSurface`: a transparent layer above the video and poster, turned with the video, takes the touches. Controls drawn above the surface take their own touches first, so they never reach the gestures. Fullscreen is the app's: `enterFullscreen()` only changes the presentation, and the app shows `FullscreenVideoPlayer` in response.

## Cross-module internals

A few public declarations exist only so other modules of this project can reach into core. They need `@OptIn(InternalVideoPlayerApi::class)`, which apps should never use:

- `VideoPlayerDiagnostics.onNativePlayerCreated`, used by the leak checks.
