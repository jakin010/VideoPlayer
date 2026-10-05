# Platform notes

Android- and iOS-specific code that needs care. For common code, see [Maintainer notes](maintainer-notes.md).

## Android (Media3)

### Engine

**Where:** `videoplayer-core/src/androidMain/.../ExoPlaybackEngine.kt`

- One `ExoPlayer` per engine, on the main looper. The playlist lives in common code; ExoPlayer only ever has one item.
- Start gating maps onto `DefaultLoadControl`. `ParkableLoadControl` wraps it and stops all loading while parked, keeping what is buffered.
- Headers and cookies go to `DefaultHttpDataSource` as default request properties, so they reach every request of the item: MP4 ranges, HLS playlists, segments and keys. Cookies are joined into one `Cookie` header, merged with any `Cookie` header the app set.
- Media3 doesn't handle audio focus (`handleAudioFocus` stays off). The coordinator does it, so several players don't take focus from each other.

### Emulator decoder workaround

**Where:** `CodecSelector` in `ExoPlaybackEngine.kt`

The emulator's goldfish H.264 decoders claim adaptive playback but corrupt frames when an HLS stream switches resolution: green and magenta blocks, a zoomed picture, a vertical seam. The selector prefers any other video decoder, which on the emulator is the software one. Real devices have no goldfish codecs, so the selector changes nothing there. If corrupted frames show up again on the emulator, check logcat for the chosen codec before touching other code.

### Surface

**Where:** `PlatformVideoSurface.android.kt`

Media3's `PlayerSurface` with a `SurfaceView` (cheapest on battery). A `SurfaceView` keeps its last frame until the next player draws, so `keepPreviousFrame` needs no work on Android. When a kept item becomes current again, ExoPlayer draws a fresh frame onto the surface even while paused, which reports the first frame again.

### Application context

**Where:** `Platform.android.kt`

`VideoPlayerContextProvider` is a content provider declared in the library manifest. It runs before `Application.onCreate` and stores the application context, so `PlayerController()` can be called from common code without passing a context.

### System integration

**Where:** `SystemIntegration.android.kt`

- One audio focus request for the whole app (`SharedAudioFocus`), held only while an unmuted player of any coordinator plays. Each coordinator votes through `CoordinatorAudio`, held weakly so a dropped coordinator stops counting. Two requests from one app take focus from each other, which is why it is shared.
- Focus changes, the headphones broadcast and the process lifecycle reach every coordinator through `SystemListeners`.
- A transient loss keeps the request, so the system can return focus; asking again during the loss tells whether it is still going on.
- If focus is denied (for example during a call), audible players pause with `Interruption`.
- `ACTION_AUDIO_BECOMING_NOISY` (headphones disconnected) is registered only while something audible plays.
- Background detection uses `ProcessLifecycleOwner`, which ignores configuration changes.

### Errors

**Where:** `ExoErrorMapping.kt`. Maps `PlaybackException` codes to categories. HTTP statuses come from `InvalidResponseCodeException`. DRM errors map to `UnsupportedFormat` (no DRM in v1).

## iOS (AVFoundation)

### Buffer gating

**Where:** `AVPlaybackEngine.kt`, `BufferGate.kt`

AVPlayer has no equivalent of Media3's start thresholds, and its own stall handling would fight ours (risk §20). The engine turns off `automaticallyWaitsToMinimizeStalling` and only sets a rate once `BufferGate` says enough is buffered ahead of the position, from the item's `loadedTimeRanges`. This is the most fragile part of the iOS engine; test it on a throttled network after any change.

- A stall is reported when `playbackBufferEmpty` turns true or `AVPlayerItemPlaybackStalledNotification` arrives while playing, except within 0.5 s of the end, where the end notification follows instead.
- Seeks use zero tolerance for exact positions. A seek superseded by a newer one doesn't report completion; the newer one does.
- Parking sets `preferredForwardBufferDuration` to 1 s, which stops further downloading while keeping the buffer, and guards the rate at 0.

### Key-value observing

**Where:** `KeyValueObservation.kt`, `src/nativeInterop/cinterop/KeyValueObserver.def`

Kotlin/Native only exposes `observeValueForKeyPath:ofObject:change:context:` as an extension on `NSObject`, which can't be overridden. The cinterop definition declares the method in a protocol (`LVPKeyValueObserver`) that a Kotlin class can implement. This needs `kotlin.mpp.enableCInteropCommonization=true` in `gradle.properties` so the shared `iosMain` source set sees it.

- Callbacks can arrive on any thread and are dispatched to the main thread.
- Every observation is removed in `detachItem()` and `release()`. A KVO observer left on a freed item crashes, so keep the invalidation when changing item handling.
- Notification observers are removed in the same places.

### Headers and cookies

Cookies use the public `AVURLAssetHTTPCookiesKey`. Custom headers have no public API: the engine uses the undocumented `AVURLAssetHTTPHeaderFieldsKey` asset option (open decision §19.1). It works in practice but isn't guaranteed. A resource-loader fallback is the documented alternative if it ever breaks.

### Surface and layer handoff

**Where:** `PlatformVideoSurface.ios.kt`

- The engine owns one `AVPlayerLayer` and moves it into the active surface's `VideoLayerHostView`, inside a `UIKitView`. Moving a layer never prepares the item again.
- When the layer changes while `keepPrevious` is set, the old layer stays underneath, still showing its last frame, until the new layer has one (a new layer is transparent until then).
- The host view's background is black: it is what letterbox bars of a fitted video show. A clear background showed thin white lines around the video in fullscreen.
- `UIKitInteropProperties(isInteractive = false)` keeps the view out of touch handling and lets Compose content draw above it.
- `AVPlayerLayer.readyForDisplay` reports the first frame. When a kept item becomes current again, its layer already holds the frame, so unparking reports it again immediately.

### Audio session

**Where:** `SystemIntegration.ios.kt`

- The session is app-wide. Each coordinator votes (`CoordinatorAudio`), and `SharedAudioSession` applies ambient or playback from the combined vote, only when the result changes.
- Deactivating the session while a muted player still runs audio output fails; that is harmless and ignored.
- An interruption deactivates the session, so the cached category is reset and applied again afterwards.
- Interruptions stop every player, muted ones included. Late interruption notifications for an app that was suspended are ignored; its players were already paused for the background.

### Errors

**Where:** `AVErrorMapping.kt`. AVPlayer doesn't put the HTTP status on the error. The mapping reads it from the item's error log; CoreMedia codes such as -12938 (HTTP 404) and -12660 (HTTP 403) are translated too. `AVFoundationErrorDomain -11850` is a server without range support, mapped to `Http`.

### Kotlin/Native details

- **Logging**: `NSLog` with a Kotlin `String` as a vararg crashes. `logWarning` passes the message as the format string, with `%` escaped. Keep it that way.
- **Imports**: many `AVPlayer` properties and methods (`rate`, `volume`, `replaceCurrentItemWithPlayerItem`) are Kotlin/Native extensions and need explicit imports.
- **Weak references** to Objective-C objects work with `kotlin.native.ref.WeakReference`, which the leak checks use for `AVPlayer`.
