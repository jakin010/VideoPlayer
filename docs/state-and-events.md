# State and events

## Status and play intent

The player's state is two independent values:

- `status`: what the engine can do right now.
- `playWhenReady`: whether the user (or app) wants playback, called play intent.

`isPlaying` is derived: `Ready` with play intent set and no audio interruption. Because intent is separate, the player never needs to remember whether it was playing before a stall: when buffering ends, it simply plays again.

| Status | Meaning |
|---|---|
| `Idle` | No item loaded, or suspended |
| `Preparing` | Loading the item until it is first ready, including the minimum buffer and automatic retries |
| `Buffering` | Was ready, now waiting for data after a stall or a seek |
| `Ready` | Can play immediately |
| `Ended` | Reached the end of the item (only without auto replay) |
| `Error` | Failed permanently, or retries are exhausted |

Rules worth knowing:

- `pause()` during `Preparing` or `Buffering` wins: finishing the load never starts playback on its own.
- A stall goes `Ready` → `Buffering` → `Ready` with intent unchanged, so playback resumes by itself.
- At the end, without auto replay, the status becomes `Ended` and intent is cleared with `PauseReason.Ended`. `play()` restarts from the beginning.
- With auto replay (the default), the end seeks to the start and keeps playing.

## Pause reasons

Each time play intent is cleared, the player records why in `pauseReason`. Only some reasons allow an automatic resume, which is what makes "a user pause is never overridden" enforceable.

| Reason | Set by | Resumes automatically |
|---|---|---|
| `User` | `pause()` | Never. Later automatic pauses don't replace it. |
| `Hold` | Press-and-hold on the video | On release, if the reason is still `Hold` |
| `Interruption` | Call, alarm, audio focus loss, headphones unplugged | Only when the system says so, see [Audio](audio-and-system.md) |
| `Background` | The app moved to the background | Only with `resumeAfterBackground` |
| `Coordinator` | Another player became the active one | Never |
| `Suspended` | The player was suspended | Never |
| `Ended` | The item reached its end | Never |

## PlayerState fields

| Field | Notes |
|---|---|
| `status`, `playWhenReady`, `pauseReason`, `isPlaying` | See above |
| `lifecycle` | `Active`, `Suspended` or `Released`, see [The player controller](player-controller.md#lifecycle) |
| `currentItemId` | `null` when no item is selected |
| `duration`, `seekableRange`, `isSeekable` | `duration` is `null` while unknown and for live streams |
| `isLive` | Live streams are detected, not supported: no duration, not seekable |
| `isSeeking` | A seek is in progress |
| `isFirstFrameRendered` | Resets on item change and after suspension; surfaces show the poster until it is true |
| `videoSize` | Native size of the video, once known |
| `volume`, `isMuted`, `playbackSpeed`, `autoReplay` | Current settings |
| `error`, `retryAttempt` | The most recent error; the retry in progress, or 0 |
| `presentation`, `isFullscreenAvailable` | `Inline` or `Fullscreen`; whether the configuration has `fullscreenEnabled` |
| `isAudioInterrupted` | A call or another app's transient audio focus is interrupting playback |

## Progress

`progress` carries `position` and `bufferedPosition` (the end of the buffered range that contains the position). It updates about every 250 ms while playing, and on every seek and item change. No timer runs while paused or suspended.

When an item changes, `progress` moves to the new item's start position before `state` names the new item, so code that reacts to the item change never reads the old item's position.

## Events

Events carry what state can't express (completion, seeks, retries, errors) and give analytics one clean stream. They are derived in common code from state transitions, so Android and iOS emit the same sequence for the same scenario. The sample's parity suite checks this, see [Testing](testing.md#checks-on-the-real-engines).

```kotlin
LaunchedEffect(controller) {
    controller.events.collect { event -> analytics.track(event.type, event.itemId, event.position) }
}
```

Every `PlayerEvent` has `playerId`, `itemId` (`null` when no item is loaded), `position`, a wall-clock `timestamp` and a `type`:

| Type | Fires when |
|---|---|
| `PlayerRegistered`, `PlayerUnregistered` | The player joins or leaves its coordinator. Only on `coordinator.events`. |
| `PlayerSuspended`, `PlayerResumed` | The lifecycle moves between `Active` and `Suspended` |
| `PlayerReleased` | `release()` or retention expiry. Nothing follows it. |
| `ItemChanged(previousItemId)` | The current item changes |
| `FirstFrameRendered(timeSinceLoad)` | The first frame of the item is shown; gives time to first frame |
| `PlaybackStarted(isFirstStart)` | Playback begins after `play()` or an automatic resume. Not when a stall ends. |
| `PlaybackPaused(reason)` | Play intent is cleared |
| `PlaybackCompleted` | The end is reached; on every loop with auto replay |
| `BufferingStarted`, `BufferingEnded(stallDuration)` | A stall during playback. Not for the initial load or seeks. |
| `SeekStarted(from, to)`, `SeekCompleted(position)` | A seek begins and lands |
| `CredentialsRefreshed(trigger)` | A new source was applied by the `SourceRefresher` or `updateSource` |
| `RetryScheduled(attempt, delay, error)` | An automatic retry is scheduled |
| `PlaybackError(error, isFinal)` | Any error; `isFinal` when the player enters `Error` |
| `PresentationChanged(from, to)` | The player moves between inline and fullscreen |

### Delivery

- Hot with no replay: a collector only sees events emitted after it subscribes. Subscribe before calling `selectItem` if you need the first events.
- Delivered on the main thread, in order per player. Emitting never blocks playback.
- Each collector has a buffer of 64 events; on overflow the oldest are dropped.
- `coordinator.events` merges the events of every player of that coordinator.
- The library sends nothing outside the app.

### Ordering guarantees

- When code reacts to a state change and immediately calls the controller (for example pausing as soon as `isPlaying` turns true), the events of that state change are emitted first. You never see `PlaybackPaused` without the `PlaybackStarted` of a playback you observed.
- `FirstFrameRendered` belongs to its load (after `ItemChanged` or `PlayerResumed`), but it can arrive before or after `PlaybackStarted`. Whether the first frame is decoded before the minimum buffer is reached depends on the decoder and the network, even on one platform. Don't rely on their relative order.
