# Getting started

## Modules

| Artifact | Use it for |
|---|---|
| `co.liebi.videoplayer:videoplayer-core` | The player: `PlayerController`, state, events, configuration, `VideoPlayerSurface` and the coordinator. Enough on its own if you build every control yourself. |
| `co.liebi.videoplayer:videoplayer-ui` | Ready-made controls, `VideoPlayer`, `FullscreenVideoPlayer` and the theme. Depends on `videoplayer-core` and exposes it. |
| `co.liebi.videoplayer:videoplayer-test` | `FakePlayerController` for tests and `@Preview`. Add it to test source sets only. |

The repository is private and not published to a remote Maven repository yet. Publish locally with `./gradlew publishToMavenLocal`, then depend on the version in `gradle.properties` (currently `0.1.0-SNAPSHOT`):

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("co.liebi.videoplayer:videoplayer-ui:0.1.0-SNAPSHOT")
        }
        commonTest.dependencies {
            implementation("co.liebi.videoplayer:videoplayer-test:0.1.0-SNAPSHOT")
        }
    }
}
```

## Platform setup

### Android

- Minimum API 24.
- The library's manifest adds the `INTERNET` permission and a small content provider that captures the application context, so controllers can be created from common code. Don't remove `VideoPlayerContextProvider` from the merged manifest, or creating a controller fails with "LiebiVideoPlayer was not initialized".
- Plain `http://` media needs a cleartext exception in your network security config. The sample allows it for one test host only (`videoplayer-sample/androidApp/src/main/res/xml/network_security_config.xml`).

### iOS

- Plain `http://` media needs an App Transport Security exception in `Info.plist`. The sample's `Info.plist` shows a per-domain exception.
- Progressive MP4 needs a server that supports HTTP range requests. Apple requires it; Android doesn't. See [Errors and retry](errors-and-retry.md#platform-differences).
- To hide the status bar in fullscreen, the Swift side of your app has to apply `FullscreenStatusBar`. See [Fullscreen](fullscreen.md#ios-status-bar).
- List the languages your app supports under `CFBundleLocalizations` in `Info.plist`. The player's strings follow the device language either way, but iOS only switches to a right-to-left layout (Arabic, Urdu) for declared languages. The sample's `Info.plist` lists all languages the player is translated into.

## A first player

A controller can outlive any composable. Keep it somewhere with a clear lifetime, such as a `ViewModel`, and release it there:

```kotlin
class VideoViewModel : ViewModel() {
    // The first item is selected right away and starts playing once enough is buffered.
    val controller = PlayerController(
        items = listOf(MediaItem(id = "intro", source = MediaSource.Url("https://example.com/intro.m3u8"))),
    )

    override fun onCleared() = controller.release()
}
```

Show it with `VideoPlayer`, which draws the video with the default controls, auto-hide and gestures:

```kotlin
@Composable
fun VideoScreen(viewModel: VideoViewModel) {
    VideoPlayer(
        controller = viewModel.controller,
        aspectRatio = VideoAspectRatio.Ratio16x9,
        poster = { MyPoster() }, // shown until the first frame; the library loads no images
    )
}
```

Call controller methods on the main thread only. State and events are delivered on the main thread.

## Fullscreen

Turn it on for the player with `PlayerConfiguration(fullscreenEnabled = true)`; until then the fullscreen button stays hidden. The library then switches the player's presentation, and your app shows `FullscreenVideoPlayer` while it is fullscreen:

```kotlin
Box(Modifier.fillMaxSize()) {
    ScreenContent() // with VideoPlayer(controller)
    val state by controller.state.collectAsState()
    if (state.presentation == Presentation.Fullscreen) FullscreenVideoPlayer(controller)
}
```

See [Fullscreen](fullscreen.md) for other places to show it, such as a navigation destination.

## Next steps

- Feeds or several players on one screen: [Feeds and the coordinator](feeds-and-coordinator.md).
- Your own colors and icons, switchable at runtime: [the theme](ui-components.md#theme).
- Your own controls: [UI components](ui-components.md).
- Reacting to playback (analytics, autoplay logic): [State and events](state-and-events.md).
