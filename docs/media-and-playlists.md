# Media and playlists

## MediaItem

```kotlin
MediaItem(
    id = "episode-1",                // stable and unique within one player; positions are remembered per ID
    source = MediaSource.Url("https://example.com/episode-1.m3u8"),
    startPosition = 30.seconds,      // used when no position is remembered for this ID
    title = "Episode 1",             // metadata for the app
    description = null,
    posterUrl = null,                // metadata only; the library loads no images
)
```

## MediaSource

| Source | Use it for |
|---|---|
| `MediaSource.Url(url, headers, cookies)` | Remote MP4 or HLS over HTTP(S) |
| `MediaSource.File(path)` | A file on the device, by absolute path |
| `MediaSource.Resource(uri)` | A Compose resource. Pass the URI from the generated accessor: `MediaSource.Resource(Res.getUri("files/intro.mp4"))`. The library can't resolve another module's resource paths itself. |

Supported formats are MP4 and HLS VOD. Live HLS is detected (`isLive`) but not supported in v1. Media the platform can't decode fails with `ErrorCategory.UnsupportedFormat`.

### Authenticated media

Headers and cookies apply to every request for the item, including HLS playlists, segments and keys:

```kotlin
MediaSource.Url(
    url = "https://cdn.example.com/video.m3u8",
    headers = mapOf("Authorization" to "Bearer $token"),
    cookies = listOf(Cookie(name = "session", value = sessionId)),
)
```

- Signed URLs need nothing else and are the most robust choice.
- Cookies use public APIs on both platforms. A cookie without `domain` uses the host of the URL.
- On iOS, custom headers rely on an undocumented `AVURLAsset` option (open spec decision §19.1). Prefer cookies or signed URLs there, and test headers on real devices.
- Credentials never appear in logs, events or `toString()`; `MediaSource.Url.toString()` drops the query string and header values.

## The playlist

```kotlin
controller.setItems(listOf(intro, episode1, episode2))
controller.selectItem("episode1")
```

- `setItems` replaces the list. Positions of items that remain are kept. If the current item is removed, the player stops and goes to `Idle`. Two items with the same ID throw `IllegalArgumentException`.
- If the current item's source changed in the new list, it re-prepares at its current position.
- `selectItem(id)` records the current position, stops the current item and loads the new one. Selecting the current item does nothing.
- With `playOnItemSelected` (the default), selecting sets play intent; otherwise the new item starts paused.

There is no automatic "next item". The app decides what plays next, for example on `PlaybackCompleted`.

### Where an item starts

1. The position remembered for its ID.
2. `MediaItem.startPosition`.
3. Zero.

Positions are remembered while the controller exists and, once suspended, for `positionRetention` (30 s). An item that was within a second of its end, or ended, starts from the beginning next time.

### Instant switch-back

Recently played items stay prepared in memory, paused, so switching back to them is instant (no loading, same position). `LifecycleConfig.keepPreparedItems` sets how many (default 1, so A → B → A is instant). Each kept item holds a native player and its buffer but stops downloading while kept. The coordinator drops kept items first when it needs room for another player.

Set `keepPreparedItems = 0` in feeds, where every player has a single item.

There is no disk cache or offline playback. That is the app's responsibility.

## Refreshing credentials

Credentials that expire can be refreshed two ways.

### Before they expire: `updateSource`

```kotlin
controller.updateSource("episode1", MediaSource.Url(newSignedUrl))
```

The current item re-prepares at its current position and `CredentialsRefreshed(Update)` fires. Other items use the new source at their next load.

### When they fail: `SourceRefresher`

Pass a refresher when creating the controller. It is called once when an item fails with HTTP 401 or 403:

```kotlin
val controller = PlayerController(
    sourceRefresher = { itemId, error -> api.freshSource(itemId) }, // return null to give up
)
```

- A returned source is applied, `CredentialsRefreshed(Failure)` fires and playback resumes at the same position.
- Returning `null` (or throwing) fails the item with `ErrorCategory.AccessDenied`.
- A second 401 or 403 for the same item fails without calling the refresher again, until `retry()` or the item is selected again.

See [Errors and retry](errors-and-retry.md) for the rest of the error handling.
