# LiebiVideoPlayer

A Compose Multiplatform library for playing local and remote video (MP4 and HLS VOD) on **Android** and **iOS** behind one common API.
Playback runs on Media3 ExoPlayer on Android and AVFoundation `AVPlayer` on iOS. Shared behavior lives in common code.

> Status: early development (v1 spec). Private repository; the API isn't stable yet.

## Usage

```kotlin
// App-owned controller, e.g. in a ViewModel. Call release() in onCleared().
val controller = PlayerController()
controller.setItems(listOf(MediaItem(id = "intro", source = MediaSource.Url("https://example.com/intro.m3u8"))))
controller.selectItem("intro")

// In composition: the video with controls, auto-hide and gestures wired
VideoPlayer(controller, aspectRatio = VideoAspectRatio.Ratio16x9, poster = { MyPoster() })
```

Feeds and other screens with several players use coordinator-owned controllers. The coordinator suspends and releases them when they scroll away, so the app doesn't release them:

```kotlin
val coordinator = remember { PlayerCoordinator(CoordinatorConfig(singleActivePlayer = true, maxActivePlayers = 3)) }

LazyColumn {
    items(videos, key = { it.id }) { video ->
        val controller = rememberPlayerController(key = video.id, coordinator = coordinator)
        LaunchedEffect(controller) {
            controller.setItems(listOf(video))
            controller.selectItem(video.id)
        }
        VideoPlayer(controller)
    }
}
```

Fullscreen needs one `FullscreenHost` per coordinator at the root of the UI, above the app content and outside any system bar padding. Until a host is placed, `enterFullscreen()` does nothing and the fullscreen button stays hidden:

```kotlin
Box(Modifier.fillMaxSize()) {
    AppContent()
    FullscreenHost() // PlayerCoordinator.Default; pass a coordinator for players that use another one
}
```

On iOS, Compose can't hide the status bar itself. The view hosting the Compose UI applies `FullscreenStatusBar.isHidden`; the sample's `ContentView.swift` shows the SwiftUI version.

## Implementation status

| Spec section | Status |
|---|---|
| §3–§9 Public API, state model, buffering, seeking, playlist and position memory, suspension and retention, errors and retry, credential refresh | Done, unit-tested in common code |
| §10 `VideoPlayerSurface`: aspect ratio, content scale, poster, surface handoff | Done |
| §15–§17 Events, configuration, `FakePlayerController` | Done |
| Instant switch-back: recently played items stay prepared in memory (`LifecycleConfig.keepPreparedItems`, default 1) | Done |
| §11 Default controls: `PlayerControls`, `PlayPauseButton`, `MuteButton`, `PlayerScrubber` | Done. UI-tested on iOS |
| §11 Auto-hide (`ControlsVisibility`), gestures (`VideoGestures`: tap, double-tap seek, hold to pause), `SeekIndicator`, `LoadingIndicator`, `ErrorPanel`, `VideoPlayer` | Done. UI-tested on iOS |
| §14 `PlayerCoordinator`: registry, merged events, single active player, native player cap, owned controllers (`rememberPlayerController`), shared iOS audio session; keep screen awake while playing | Done, unit-tested in common code |
| §12 Fullscreen: `FullscreenHost`, `FullscreenButton`, `enterFullscreen()` / `exitFullscreen()`, Back, custom controls slot | Done. Unit- and UI-tested |
| §13 Audio focus (Android), interruptions, headphones disconnecting, pausing in the background with `resumeAfterBackground` | Done, unit-tested in common code |
| §17 Parity suite and leak checks on the real engines (sample's Checks tab) | Done. Passing on the Android emulator and iOS simulator |

Known platform behavior:
- iOS needs HTTP byte-range support for progressive MP4 (an Apple requirement). Hosts that ignore `Range` fail on iOS with `ErrorCategory.Http` (`AVFoundationErrorDomain -11850`); Android plays them. The fix is on the server.
- iOS custom headers use the undocumented `AVURLAssetHTTPHeaderFieldsKey` option (open decision §19.1). Cookies and signed URLs are the robust choices.
- `MediaSource.Resource` takes the URI from the generated resource accessor, `Res.getUri("files/intro.mp4")`. The library can't resolve another module's resource paths itself.
- Muted players never interrupt other apps' audio. On iOS the coordinator keeps the audio session ambient (mixing with other apps, silenced by the ring switch) until an unmuted player plays, then switches to playback. On Android it requests audio focus only while an unmuted player plays, one request per coordinator. Set `CoordinatorConfig.manageAudioSession = false` if the app manages the session or focus itself.
- Interruptions: a call or alarm pauses with `PauseReason.Interruption` and resumes when the system allows it. On iOS this stops every player, muted ones included, because the whole session is interrupted. On Android it stops only audible players; ducking is left to the system. Losing focus for good, or headphones disconnecting, pauses without resuming.
- The app moving to the background pauses every player with `PauseReason.Background`. Background playback is planned for v1.1.
- The native player cap counts each player's current item plus items kept prepared. When every other player is playing, the cap is exceeded and a warning is logged rather than stopping playback.
- On the Android emulator, video uses the software decoder: the emulator's goldfish decoder corrupts frames when HLS switches resolution. Real devices are unaffected.
- There is no disk cache or offline playback; that is the app's responsibility.

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| [`videoplayer-core`](videoplayer-core) | `co.liebi.videoplayer:videoplayer-core` | Public API (`PlayerController`, state, events, configuration), `VideoPlayerSurface`, and the platform playback engines (Media3 / AVFoundation). The common state machine is in `internal/DefaultPlayerController.kt` |
| [`videoplayer-ui`](videoplayer-ui) | `co.liebi.videoplayer:videoplayer-ui` | Default controls, `VideoPlayer`, gestures, fullscreen host and localizable strings. Optional: apps with fully custom controls only need `videoplayer-core` |
| [`videoplayer-test`](videoplayer-test) | `co.liebi.videoplayer:videoplayer-test` | Test doubles (`FakePlayerController`) for app tests and `@Preview` |
| [`videoplayer-sample`](videoplayer-sample) | not published | Android and iOS test app. The Player tab has HLS, MP4 and a broken URL with live state and an event log. The Coordinator tab is a feed of five streams with single-active, cap and autoplay switches and the merged event log. The Checks tab runs the parity suite and the leak checks. All players start muted |
| [`build-logic`](build-logic) | not published | Gradle convention plugins |

```
videoplayer-ui ──► videoplayer-core ◄── videoplayer-test
       ▲                  ▲
       └── videoplayer-sample
```

## Requirements

- Android SDK: set `sdk.dir` in `local.properties`, or open the project in Android Studio and it sets this for you.
- Xcode, for the iOS sample.
- JDK: Gradle provisions JDK 21 for its daemon automatically (see `gradle/gradle-daemon-jvm.properties`).

## Building

| Task | Command |
|---|---|
| Android sample | `./gradlew :videoplayer-sample:androidApp:installDebug` |
| iOS sample | Open `videoplayer-sample/iosApp/iosApp.xcodeproj` in Xcode and run it |
| All tests | `./gradlew allTests` |
| Core tests on JVM / iOS simulator | `./gradlew :videoplayer-core:testAndroidHostTest` / `./gradlew :videoplayer-core:iosSimulatorArm64Test` |
| Publish to Maven Local | `./gradlew publishToMavenLocal` |

## Checks on the real engines

The sample's Checks tab runs the §17 suites against Media3 and AVFoundation, with real surfaces and network media. Each result is shown on screen and printed as a `LVP-CHECK|…` line.

- **Parity suite**: ten scripted scenarios (play and pause, pause before ready, seek, switching items and back, end with and without auto replay, a 404, suspend and resume, hold, fullscreen). Each one must emit exactly the expected event sequence, which is shared by both platforms, so passing on both means the sequences are identical. Buffering events are left out because they depend on the network. `FirstFrameRendered` is compared within its load, because whether the first frame is decoded before or after the minimum buffer depends on the decoder.
- **Leak checks**: 100 create, load, suspend and release cycles, and a 100-item feed scroll. Afterwards every released controller and every native player (ExoPlayer, AVPlayer) must be unreachable after garbage collection. Debug builds of the Android sample also hand released players to LeakCanary.

Run them from the tab, or at launch with `parity`, `cycles`, `feed`, `leaks` (cycles and feed) or `all`. Always name the emulator explicitly:

```bash
adb -s emulator-5554 shell am start -S -n co.liebi.videoplayer.sample/.MainActivity --es checks all
```

```bash
xcrun simctl launch --console-pty booted co.liebi.videoplayer.sample -checks all
```

On Android the lines appear in `adb -s emulator-5554 logcat -s System.out`. Instruments' Leaks template can't scan iOS simulator processes on this setup, so the iOS leak check relies on the weak references above.

## Project conventions

- Library modules apply the `liebi.kmp.library` convention plugin from `build-logic`. It sets up:
  - the Android, `iosArm64` and `iosSimulatorArm64` targets
  - explicit API mode
  - JVM 11 bytecode
  - `maven-publish`
  - an Android namespace taken from the module name (`videoplayer-core` → `co.liebi.videoplayer.core`)
- Dependency versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Group and version live in [`gradle.properties`](gradle.properties).
- UI strings and icons are Compose resources in `videoplayer-ui/src/commonMain/composeResources/`. Strings go in `values/` and icons in `drawable/`.
