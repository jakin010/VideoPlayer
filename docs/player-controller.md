# The player controller

`PlayerController` controls one player. It is an interface, so tests and previews can use `FakePlayerController` from `videoplayer-test`.

## Creating a controller

There are two ways, and the difference is who releases it.

| | App-owned | Coordinator-owned |
|---|---|---|
| Create with | `PlayerController(configuration, sourceRefresher, coordinator, items, selectedItemId)` | `rememberPlayerController(key, coordinator, configuration, sourceRefresher, items, selectedItemId)` in composition, or `coordinator.controllerFor(key, …)` |
| Release | The app calls `release()`, for example in `ViewModel.onCleared` | The coordinator releases it after it has been off screen for `positionRetention` (30 s) |
| Good for | A screen with one player, a player that must survive navigation | Lazy lists and feeds |

Both kinds survive Android configuration changes such as rotation: the app-owned one because it lives in a `ViewModel`, the coordinator-owned one because the coordinator holds it by key. For the second, the coordinator itself must survive too: use `PlayerCoordinator.Default` or keep yours in a `ViewModel`. A controller created in a plain `remember {}` does not survive and should not be used.

`configuration` is fixed when the controller is created. For coordinator-owned controllers, `configuration`, `sourceRefresher` and `items` only apply when the key is first used. See [Configuration](configuration.md).

`items` sets the playlist right away and selects `selectedItemId`, the first item unless given; pass `selectedItemId = null` to select nothing yet. It is the same as calling `setItems` and `selectItem` after creating the controller, so selecting starts playback when `playOnItemSelected` is on. A `selectedItemId` that isn't among the items throws.

## Threading

Call every method on the main thread. `state`, `progress` and `events` are delivered on the main thread.

## Commands

| Command | Effect |
|---|---|
| `play()` | Sets play intent. Playback starts once the status is `Ready`. In `Ended` it restarts from the beginning; in `Error` it retries; while suspended it resumes. |
| `pause()` | Clears play intent with `PauseReason.User`, in any status. Nothing automatic overrides a user pause. |
| `seekTo(position)` | Clamped to the seekable range, ignored when the media can't seek. Before the item is ready, the target is stored and applied once it is. Rapid calls coalesce. |
| `setVolume(volume)` / `setMuted(muted)` | Player gain from 0 to 1, not the system volume. Muting keeps the volume, so unmuting restores it. |
| `setPlaybackSpeed(speed)` | 0.5x to 2.0x with pitch preserved. Kept across items. |
| `setAutoReplay(enabled)` | Loop the item at its end. Kept across items. |
| `setItems(items)` / `selectItem(id)` | The playlist. See [Media and playlists](media-and-playlists.md). |
| `updateSource(itemId, source)` | Swap a source, for example to refresh credentials before they expire. |
| `retry()` | Re-prepare after an error and reset the retry counter. |
| `beginHold()` / `endHold()` | Pause for a press-and-hold and resume on release, used by the gestures. Resumes only if nothing else paused the player in between. |
| `enterFullscreen()` / `exitFullscreen()` | See [Fullscreen](fullscreen.md). |
| `suspend()` | Release the native player now but keep items, positions and settings. `play()` or attaching a surface resumes. |
| `release()` | Permanent. Idempotent; later commands are ignored and logged as warnings. |

## Reading the player

- `state: StateFlow<PlayerState>`: everything except the position. See [State and events](state-and-events.md).
- `progress: StateFlow<PlaybackProgress>`: position and buffered position, about every 250 ms while playing. It is separate so only position-dependent UI recomposes on every tick.
- `events: Flow<PlayerEvent>`: this player's events. Hot, with no replay.
- `id`: identifies the player in events, for example `player-3`.

In Compose, read them with `collectAsState()`:

```kotlin
val state by controller.state.collectAsState()
if (state.status == PlaybackStatus.Error) { /* … */ }
```

## Lifecycle

| Lifecycle | Native player | Kept | Entered when |
|---|---|---|---|
| `Active` | Allocated, item prepared | Everything | Created, a surface attaches, or `play()` while suspended |
| `Suspended` | Released | Items, current item, positions, volume, mute, speed | No surface for about 1 s, `suspend()`, or the coordinator's player cap |
| `Released` | Released | Nothing | `release()`, or retention expiring for a coordinator-owned controller |

Suspension is what makes feeds affordable: a player that scrolls away frees its decoder but comes back at the same position. Leaving `Suspended` prepares the item again, paused unless `play()` triggered it. The poster shows until the first frame.

A suspended controller keeps its positions for `positionRetention` (30 s). After that, positions are dropped and items restart from their `startPosition`.

## Surfaces

A controller renders to one surface at a time: the most recently attached `VideoPlayerSurface`. Others show the poster. When the last surface leaves composition, the controller suspends after about a second, so scrolling back and forth doesn't restart the decoder. Entering fullscreen attaches the fullscreen surface before the inline one leaves, so it never suspends.
