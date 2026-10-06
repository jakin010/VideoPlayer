# Errors and retry

## PlayerError

Platform errors are normalized into a `PlayerError`:

| Field | Meaning |
|---|---|
| `category` | One of the categories below |
| `isRecoverable` | Retried automatically before the player enters `Error` |
| `message` | Human-readable; never contains URLs or credentials |
| `httpStatus` | The HTTP status, when there is one |
| `platformDetail` | Native error domain and code, for debugging only |

| Category | Covers | Recoverable |
|---|---|---|
| `Network` | Timeout, no connection, connection reset, DNS failure | Yes |
| `Http` | HTTP errors; 5xx, 408 and 429 are recoverable, other 4xx are not | Depends |
| `AccessDenied` | HTTP 401 or 403 (after one credential refresh), or an unreadable local file | No |
| `NotFound` | HTTP 404 or 410, or a missing file or resource | No |
| `UnsupportedFormat` | A container or codec the platform can't play | No |
| `Decoder` | The decoder failed to start or failed during playback, including decoder limits | Yes |
| `Unknown` | Anything else | Yes |

`state.error` holds the most recent error. Show your own UI for it through the `error` slot of `VideoPlayer`, or read `state` directly.

## Automatic retry

1. Retry starts only after the engine reports a fatal error. ExoPlayer and AVPlayer already retry individual segment requests internally; the library doesn't stack on top of that.
2. On a recoverable error: the status goes to `Preparing`, `retryAttempt` increments, `PlaybackError(isFinal = false)` and `RetryScheduled` fire, and after `RetryConfig.delay` the item is prepared again at the position where it failed.
3. After `maxAttempts` (3), or on a non-recoverable error: the status becomes `Error` and `PlaybackError(isFinal = true)` fires.
4. The error stays until `retry()`, `play()`, `selectItem()` or `setItems()`.
5. The counter resets once playback gets a second past the point where it failed, and on item change.
6. `retry()` resets the counter and prepares again immediately. The default `ErrorPanel` calls it.

A seek made while recovering wins over the position where the error happened.

## Expiring credentials

HTTP 401 and 403 can be handled before they become errors. See [Media and playlists](media-and-playlists.md#refreshing-credentials) for `SourceRefresher` and `updateSource`.

## Platform differences

The mapping code is in `ExoErrorMapping.kt` (Android) and `AVErrorMapping.kt` (iOS). Known differences:

- **Range requests**: iOS needs HTTP range support for progressive MP4 (an Apple requirement). Hosts that ignore `Range` fail on iOS with `ErrorCategory.Http` (`AVFoundationErrorDomain -11850`) while Android plays them. The fix is on the server.
- **HTTP status on iOS**: AVPlayer doesn't report the status on the error itself. The iOS mapping reads it from the item's error log, which is where failed HLS and progressive requests show up.
- **DRM**: not supported in v1. Protected content fails as `UnsupportedFormat` on both platforms.

## Logging

The library logs warnings with the `LiebiVideoPlayer` tag on Android and a `[LiebiVideoPlayer]` prefix in the iOS console: commands sent to a released player, unknown item IDs, `enterFullscreen()` without `fullscreenEnabled`, a refresher that threw, and the player cap being exceeded. Its own messages never contain URLs, header values or cookie values. Media3 and AVFoundation write their own logs, which the library doesn't control.
