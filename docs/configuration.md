# Configuration

Every default lives in `PlayerConfiguration` and `CoordinatorConfig`. A player's configuration is fixed when the controller is created; runtime changes go through controller methods (volume, mute, speed, auto replay, items).

```kotlin
val feedConfiguration = PlayerConfiguration(
    playback = PlaybackConfig(initialMuted = true, playOnItemSelected = false),
    lifecycle = LifecycleConfig(keepPreparedItems = 0),
)
val controller = PlayerController(feedConfiguration)
```

Aspect ratio and content scale are not configuration: they are parameters of `VideoPlayer` and `VideoPlayerSurface`, because one controller can render into surfaces of different shapes.

## PlaybackConfig

| Option | Default | Meaning |
|---|---|---|
| `autoReplay` | `true` | Loop at the end. Change at runtime with `setAutoReplay`. |
| `playOnItemSelected` | `true` | `selectItem` sets play intent. Turn off for feeds where something else decides what plays. |
| `initialMuted` | `false` | Feeds usually start muted. Muted players never interrupt other apps' audio. |
| `initialVolume` | `1` | 0 to 1, player gain. |
| `initialPlaybackSpeed` | `1` | 0.5 to 2.0. |

## BufferingConfig

The minimum media buffered ahead of the position before playback starts. When less media remains than a threshold, the remaining duration is used instead, so short clips still start.

| Option | Default | Applies to |
|---|---|---|
| `minBufferToStart` | 2.5 s | The first start, after a seek and after selecting an item |
| `minBufferAfterRebuffer` | 5 s | Resuming after a stall during playback |

Larger values mean fewer stalls but a slower start.

## RetryConfig

| Option | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Retry recoverable errors automatically |
| `maxAttempts` | 3 | Attempts before the player enters `Error` |
| `delay` | 2 s | Fixed delay before each attempt |

See [Errors and retry](errors-and-retry.md).

## LifecycleConfig

| Option | Default | Meaning |
|---|---|---|
| `resumeAfterBackground` | `false` | On return from the background, resume players still paused for it |
| `keepScreenAwakeWhilePlaying` | `true` | Keep the screen on while this player plays on a surface |
| `positionRetention` | 30 s | How long a suspended controller keeps positions. Coordinator-owned controllers are released when it runs out. |
| `keepPreparedItems` | 1 | Previously played items kept prepared for instant switch-back. 0 turns it off. |

## CoordinatorConfig

Passed to `PlayerCoordinator(config)`. See [Feeds and the coordinator](feeds-and-coordinator.md).

| Option | Default | Meaning |
|---|---|---|
| `singleActivePlayer` | `false` | Starting a player pauses every other playing player with `PauseReason.Coordinator` |
| `maxActivePlayers` | 4 | How many players may hold a native player at once, counting kept items. Protects against hardware decoder limits. Measure on low-end Android devices before changing it. |
| `manageAudioSession` | `true` | Let the coordinator manage the iOS audio session and Android audio focus. Turn off if the app manages them itself. |

## Fixed values

These are implementation constants rather than options:

| Value | Where |
|---|---|
| Progress updates about every 250 ms while playing | Controller |
| Suspension about 1 s after the last surface leaves | Controller |
| Controls hide 3 s after the last interaction | `hideControlsAfter` on `VideoPlayer`, `FullscreenHost` and `rememberControlsVisibility` |
| Double-tap seek step 10 s, middle dead zone 20 % | `DoubleTapSeek(step, deadZone)` |
| Poster appears after 1 s without a first frame once video has shown | `posterDelay` on `VideoPlayerSurface` |
