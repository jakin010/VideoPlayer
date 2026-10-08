# LiebiVideoPlayer documentation

LiebiVideoPlayer plays local and remote video (MP4 and HLS VOD) on Android and iOS from Compose Multiplatform. Playback runs on Media3 ExoPlayer on Android and AVFoundation `AVPlayer` on iOS. All player behavior lives in common code, so both platforms act the same.

The project follows the "Compose Multiplatform Video Player — v1 Spec". Section numbers such as §8 in the code and these pages refer to that spec.

## Using the library

Read these in order the first time.

| Page | What it covers |
|---|---|
| [Getting started](getting-started.md) | Modules, platform setup, a first player on screen |
| [The player controller](player-controller.md) | Creating, owning and releasing players; the commands |
| [State and events](state-and-events.md) | Status, play intent, pause reasons, progress and the event stream |
| [Media and playlists](media-and-playlists.md) | Media items and sources, headers and cookies, switching items, credential refresh |
| [Configuration](configuration.md) | Every option and its default |
| [UI components](ui-components.md) | `VideoPlayer`, the surface, pan, zoom and rotation, controls, the theme (colors and icons), auto-hide and gestures |
| [Fullscreen](fullscreen.md) | Showing `FullscreenVideoPlayer`, the fullscreen controls, swiping, turning the view and the iOS status bar |
| [Feeds and the coordinator](feeds-and-coordinator.md) | Many players at once: owned controllers, single active player, the player cap |
| [Audio and system integration](audio-and-system.md) | Muted playback, audio focus, interruptions, background, keeping the screen awake |
| [Errors and retry](errors-and-retry.md) | Error categories, automatic retries and expiring credentials |
| [Testing](testing.md) | `FakePlayerController`, unit and UI tests, and the checks on the real engines |

## Working on the library

| Page | What it covers |
|---|---|
| [Architecture](architecture.md) | How the modules, the common state machine and the platform engines fit together |
| [Maintainer notes](maintainer-notes.md) | Common code that needs extra care, and why it is written the way it is |
| [Platform notes](platform-notes.md) | The Android and iOS engines, surfaces and system integration in detail |
| [Development](development.md) | Building, running the samples, conventions, emulator and simulator tips |
