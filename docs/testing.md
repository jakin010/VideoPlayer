# Testing

## Testing your app with FakePlayerController

`FakePlayerController` from `videoplayer-test` is a `PlayerController` without a native player, for UI tests and `@Preview`. Commands change its state the obvious way and are recorded:

```kotlin
val controller = FakePlayerController(
    initialState = PlayerState(status = PlaybackStatus.Ready, duration = 100.seconds, isSeekable = true),
    initialProgress = PlaybackProgress(position = 50.seconds),
)

controller.play()
assertTrue(controller.state.value.playWhenReady)
assertEquals(listOf(FakePlayerController.Call.Play), controller.calls)
```

- `calls` lists every command received, in order.
- `setState`, `updateState`, `setProgress` and `emit(type)` drive it to any situation, such as an error or a stall.
- `enterFullscreen()` only switches the presentation when `isFullscreenAvailable` is true, like a real player.
- It runs no timers and no playback: progress only moves when you set it.

For previews, pass a fake in the state you want to show:

```kotlin
@Preview
@Composable
fun ErrorPreview() {
    VideoPlayer(FakePlayerController(PlayerState(status = PlaybackStatus.Error, error = PlayerError(ErrorCategory.Network, true, "Offline"))))
}
```

## The library's own tests

| Suite | Where | Runs on |
|---|---|---|
| State machine, coordinator, buffer gate | `videoplayer-core/src/commonTest` | JVM and the iOS simulator |
| Scrubber math, seek zones | `videoplayer-ui/src/commonTest` | JVM and the iOS simulator |
| Translations complete, with the same placeholders as English | `videoplayer-ui/src/androidHostTest` | JVM |
| Controls, gestures, fullscreen UI | `videoplayer-ui/src/iosTest` | The iOS simulator |
| `FakePlayerController` | `videoplayer-test/src/commonTest` | JVM and the iOS simulator |

```bash
./gradlew allTests
```

### Common-code tests

`DefaultPlayerControllerTest` and `PlayerCoordinatorTest` run the real controller against `FakePlaybackEngine`, a scriptable engine whose callbacks the test drives (`becomeReady()`, `stall()`, `end()`, `fail(error)`), with a virtual clock from `kotlinx-coroutines-test`. Behavior belongs here first: if it is in common code, it is tested once for both platforms.

To mimic app code that reacts on the main thread while a state change is being made, collect with `UnconfinedTestDispatcher`. Two tests in the "Re-entrant observers" region do this.

### UI tests

Compose UI tests run on the iOS simulator; Android host tests would need Robolectric. They use `runComposeUiTest` from `androidx.compose.ui.test.v2`. While playing, the scrubber animates every frame, so tests set `mainClock.autoAdvance = false` and advance the clock by hand; otherwise the test never goes idle.

## Checks on the real engines

Unit tests can't show that Media3 and AVFoundation report what the state machine expects. The sample's Checks tab runs two suites from the spec on the real engines, with real surfaces and network media.

### Parity suite

Eleven scripted scenarios: play and pause, pause before ready, seek, switching items and back, end with and without auto replay, a 404, suspend and resume, hold, two coordinators playing with sound, and fullscreen. Each one has an expected event sequence that both platforms share. Passing on both means Android and iOS emit identical sequences. Some scenarios also check timing, such as resuming within 0.5 s when switching back.

Events are compared without timestamps, with positions rounded to seconds and without buffering events (they depend on the network). `FirstFrameRendered` is compared within its load, see [State and events](state-and-events.md#ordering-guarantees).

The scenarios live in `videoplayer-sample/shared/src/commonMain/kotlin/co/liebi/videoplayer/sample/checks/ParityScenarios.kt`. A new scenario is a name, the expected tokens and a script:

```kotlin
ParityScenario(
    name = "seek",
    expected = listOf("ItemChanged(null)", "FirstFrameRendered", "PlaybackStarted(first=true)",
        "SeekStarted(to=30s)", "SeekCompleted(30s)", "PlaybackPaused(User)", "PlayerReleased"),
) {
    load(Hls)
    awaitPosition(2.seconds)
    controller.seekTo(30.seconds)
    awaitEvent("seek completed") { it is PlayerEventType.SeekCompleted }
    controller.pause()
}
```

### Leak checks

- **100 cycles**: create a player, load until the first frame on a surface, suspend, release.
- **Feed scroll**: scroll a 100-item feed of coordinator-owned players, playing the most visible one, then release them all.

Afterwards every released controller and every native player (ExoPlayer, AVPlayer) must be unreachable after garbage collection. The native players are reported through `VideoPlayerDiagnostics`, an opt-in hook for test tooling. A leaked surface or a forgotten observer keeps its native player alive, so this catches more than the controllers alone. Debug builds of the Android sample also hand released players to LeakCanary.

Instruments' Leaks template can't scan iOS simulator processes on our setup, which is why the iOS check relies on weak references.

### Running the checks

From the Checks tab (Run all, Parity, 100 cycles, Feed scroll), or at launch with `parity`, `parity:<name>,<name>` for some scenarios, `cycles`, `feed`, `leaks` (cycles and feed) or `all`:

```bash
adb -s emulator-5554 shell am start -S -n co.liebi.videoplayer.sample/.MainActivity --es checks all
```

```bash
xcrun simctl launch --console-pty booted co.liebi.videoplayer.sample -checks all
```

Each result prints one line, `LVP-CHECK|parity|seek|PASS|…`, and the run ends with `LVP-CHECK|DONE|passed=…|failed=…`. On Android read them with `adb -s emulator-5554 logcat -s System.out`. The full run takes about ten minutes and needs network access.

All players in the checks are muted, except in `two-coordinators-audible`, which needs players that count as playing with sound: they play at 0.1 % volume, which can't be heard. See [Development](development.md#devices) for how to run emulators safely.
