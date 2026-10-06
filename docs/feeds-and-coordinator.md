# Feeds and the coordinator

`PlayerCoordinator` owns everything that spans players: the registry, coordinator-owned controllers, the single active player rule, the native player cap, which player is fullscreen, audio focus and interruptions, backgrounding, and one merged event stream.

Every controller belongs to exactly one coordinator. `PlayerCoordinator.Default` is used unless you pass another one. Most apps need only the default. Use a separate coordinator when a screen needs different rules, for example a feed where only one video may play.

## A feed

```kotlin
class FeedViewModel : ViewModel() {
    // Survives rotation, so the players it owns do too.
    val coordinator = PlayerCoordinator(CoordinatorConfig(singleActivePlayer = true, maxActivePlayers = 3))

    override fun onCleared() = coordinator.releaseAll()
}

private val FeedConfiguration = PlayerConfiguration(
    playback = PlaybackConfig(initialMuted = true, playOnItemSelected = false),
    lifecycle = LifecycleConfig(keepPreparedItems = 0),
)

@Composable
fun Feed(videos: List<MediaItem>, viewModel: FeedViewModel) {
    LazyColumn {
        items(videos, key = { it.id }) { video ->
            val controller = rememberPlayerController(
                key = video.id,
                coordinator = viewModel.coordinator,
                configuration = FeedConfiguration,
                items = listOf(video), // only used when the controller is created
            )
            // Swiping up on a video should scroll the feed, not enter fullscreen.
            VideoPlayer(controller, gestures = VideoGestures(swipeToFullscreen = false))
        }
    }
}
```

For fullscreen in the feed, add `fullscreenEnabled = true` to `FeedConfiguration` and show the coordinator's fullscreen player above the list. A coordinator has at most one:

```kotlin
Box(Modifier.fillMaxSize()) {
    Feed(videos, viewModel)
    val player by viewModel.coordinator.fullscreenPlayer.collectAsState()
    player?.let { FullscreenVideoPlayer(it) }
}
```

Turn `swipeToFullscreen` off for feed players, as above. A swipe up that starts on a video otherwise enters fullscreen instead of scrolling the list. The fullscreen button and swiping down in fullscreen keep working.

### What happens while scrolling

1. An item composes and gets its controller by key. The item starts loading, paused.
2. An item scrolls away: its surface leaves, and about a second later the controller suspends and frees its decoder. Scrolling back within that second cancels it.
3. Scrolling back later: the surface attaches, the controller resumes and loads at the remembered position. The poster shows until the first frame.
4. After `positionRetention` (30 s) off screen, the coordinator releases the controller. If the item comes back, `rememberPlayerController` creates a fresh one.

Don't release coordinator-owned controllers yourself. To drop all of them at once, call `coordinator.releaseAll()`.

### Autoplay

The library doesn't decide which feed item plays; that's app logic. A common pattern plays the most visible item:

```kotlin
val listState = rememberLazyListState()
val mostVisible by remember { derivedStateOf { listState.mostVisibleItemKey() } }
// in each item:
LaunchedEffect(controller, video.id == mostVisible) {
    if (video.id == mostVisible) controller.play() else controller.pause()
}
```

The sample's Coordinator tab has a complete version, including `mostVisibleItemKey()`.

## Single active player

With `singleActivePlayer = true`, a player getting play intent (`play()`, or `selectItem` with `playOnItemSelected`) pauses every other playing player with `PauseReason.Coordinator`. A player paused that way does not resume when the other one stops.

## The native player cap

`maxActivePlayers` (default 4) limits how many native players exist at once, counting items kept for instant switch-back. Hardware decoders are limited, especially on low-end Android devices. When a new player needs one and the cap is reached, the coordinator:

1. drops kept items, least recently used players first,
2. then suspends the least recently used players that are not playing,
3. and if every other player is playing, goes over the cap and logs a warning rather than stopping playback.

A suspended player comes back when it is played or its surface attaches, which may suspend another idle one in turn.

## Merged events

`coordinator.events` merges the events of all its players, plus `PlayerRegistered` and `PlayerUnregistered`. Use it for feed-wide analytics. `coordinator.players` lists the registered, unreleased players.

## Several coordinators

Each coordinator has its own players, rules and fullscreen player. Audio is shared: the iOS audio session and the Android audio focus are app-wide, so coordinators vote, and the app plays with sound while any coordinator has an unmuted player playing. Players of different coordinators never pause each other for audio. See [Audio and system integration](audio-and-system.md#several-coordinators).
