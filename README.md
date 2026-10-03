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
| §12 Fullscreen, §13 audio focus, interruptions and background | Not started |

Known platform behavior:
- iOS needs HTTP byte-range support for progressive MP4 (an Apple requirement). Hosts that ignore `Range` fail on iOS with `ErrorCategory.Http` (`AVFoundationErrorDomain -11850`); Android plays them. The fix is on the server.
- iOS custom headers use the undocumented `AVURLAssetHTTPHeaderFieldsKey` option (open decision §19.1). Cookies and signed URLs are the robust choices.
- `MediaSource.Resource` takes the URI from the generated resource accessor, `Res.getUri("files/intro.mp4")`. The library can't resolve another module's resource paths itself.
- On iOS the coordinator keeps the audio session ambient (mixing with other apps, silenced by the ring switch) until an unmuted player plays, then switches to playback. Set `CoordinatorConfig.manageAudioSession = false` if the app manages the session itself.
- The native player cap counts each player's current item plus items kept prepared. When every other player is playing, the cap is exceeded and a warning is logged rather than stopping playback.
- On the Android emulator, video uses the software decoder: the emulator's goldfish decoder corrupts frames when HLS switches resolution. Real devices are unaffected.
- There is no disk cache or offline playback; that is the app's responsibility.

## Modules

| Module | Artifact | Purpose |
|---|---|---|
| [`videoplayer-core`](videoplayer-core) | `co.liebi.videoplayer:videoplayer-core` | Public API (`PlayerController`, state, events, configuration), `VideoPlayerSurface`, and the platform playback engines (Media3 / AVFoundation). The common state machine is in `internal/DefaultPlayerController.kt` |
| [`videoplayer-ui`](videoplayer-ui) | `co.liebi.videoplayer:videoplayer-ui` | Default controls, `VideoPlayer`, gestures, fullscreen host and localizable strings. Optional: apps with fully custom controls only need `videoplayer-core` |
| [`videoplayer-test`](videoplayer-test) | `co.liebi.videoplayer:videoplayer-test` | Test doubles (`FakePlayerController`) for app tests and `@Preview` |
| [`videoplayer-sample`](videoplayer-sample) | not published | Android and iOS test app. The Player tab has HLS, MP4 and a broken URL with live state and an event log. The Coordinator tab is a feed of five streams with single-active, cap and autoplay switches and the merged event log. All players start muted |
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

## Project conventions

- Library modules apply the `liebi.kmp.library` convention plugin from `build-logic`. It sets up:
  - the Android, `iosArm64` and `iosSimulatorArm64` targets
  - explicit API mode
  - JVM 11 bytecode
  - `maven-publish`
  - an Android namespace taken from the module name (`videoplayer-core` → `co.liebi.videoplayer.core`)
- Dependency versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Group and version live in [`gradle.properties`](gradle.properties).
- UI strings and icons are Compose resources in `videoplayer-ui/src/commonMain/composeResources/`. Strings go in `values/` and icons in `drawable/`.
