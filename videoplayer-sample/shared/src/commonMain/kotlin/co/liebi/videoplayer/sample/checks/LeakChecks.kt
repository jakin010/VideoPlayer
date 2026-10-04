package co.liebi.videoplayer.sample.checks

import co.liebi.videoplayer.core.InternalVideoPlayerApi
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerLifecycle
import co.liebi.videoplayer.core.VideoPlayerDiagnostics
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal data class LeakResult(
    val name: String,
    val players: Int,
    /** Players still reachable after garbage collection. */
    val alive: Int,
    /** Native players (ExoPlayer, AVPlayer) created, and those still alive after garbage collection. */
    val nativePlayers: Int,
    val nativeAlive: Int,
    /** Players the coordinator still lists. */
    val registered: Int,
    /** Which players are still reachable. */
    val problems: List<String>,
    /** Things worth knowing that are not leaks, such as slow loads. */
    val notes: List<String> = emptyList(),
) {
    val passed: Boolean get() = alive == 0 && nativeAlive == 0 && registered == 0 && problems.isEmpty()
}

/** A released player, named for the report, held weakly. */
internal class Released(val name: String, val player: WeakRef<PlayerController>)

/**
 * Holds every native player the library creates while [block] runs, weakly. A leaked surface or a forgotten
 * observer keeps its native player alive, so these catch more than the controllers alone.
 */
@OptIn(InternalVideoPlayerApi::class)
internal inline fun <T> trackNativePlayers(natives: MutableList<WeakRef<Any>>, block: () -> T): T {
    VideoPlayerDiagnostics.onNativePlayerCreated = { natives += weakRef(it) }
    try {
        return block()
    } finally {
        VideoPlayerDiagnostics.onNativePlayerCreated = null
    }
}

/**
 * Acceptance (§18): no leaked players, surfaces or observers after 100 create, suspend and release cycles.
 * Each player loads its item on screen until the first frame, is suspended, then released.
 */
internal suspend fun runCycles(
    coordinator: PlayerCoordinator,
    count: Int,
    show: (PlayerController?) -> Unit,
    onProgress: (Int) -> Unit,
): LeakResult {
    val released = mutableListOf<Released>()
    val natives = mutableListOf<WeakRef<Any>>()
    val notes = mutableListOf<String>()
    trackNativePlayers(natives) {
        repeat(count) { index ->
            onProgress(index + 1)
            released += runCycle(index, coordinator, show, notes)
        }
    }
    return measureLeaks("cycles", released, natives, coordinator, notes)
}

/**
 * One create, load, suspend and release cycle. A function of its own so the player is not kept in the caller's
 * coroutine frame: Kotlin/Native keeps dead locals there while the caller is suspended, which would keep the last
 * player reachable and fail the check without any leak in the library.
 */
private suspend fun runCycle(
    index: Int,
    coordinator: PlayerCoordinator,
    show: (PlayerController?) -> Unit,
    notes: MutableList<String>,
): Released {
    val controller = PlayerController(Muted, coordinator = coordinator)
    // Alternate between the two engines' main paths: adaptive HLS and progressive MP4.
    val item = if (index % 2 == 0) Hls else Mp4
    controller.setItems(listOf(item))
    controller.selectItem(item.id)
    show(controller)
    val rendered = withTimeoutOrNull(20.seconds) {
        controller.state.first { it.isFirstFrameRendered || it.status == PlaybackStatus.Error }
    }
    // A slow load still exercises create, suspend and release, so it is noted rather than failed.
    if (rendered == null || rendered.status == PlaybackStatus.Error) notes += "cycle ${index + 1}: no first frame"
    controller.suspend()
    controller.state.first { it.lifecycle == PlayerLifecycle.Suspended }
    controller.release()
    show(null)
    ChecksHooks.watchReleased(controller, "player released by cycle ${index + 1}")
    // Let the surface leave composition before the next player arrives.
    delay(100.milliseconds)
    return Released("cycle ${index + 1}", weakRef(controller))
}

/** Collects garbage a few times, then counts the released players that are still reachable. */
internal suspend fun measureLeaks(
    name: String,
    released: List<Released>,
    natives: List<WeakRef<Any>>,
    coordinator: PlayerCoordinator,
    notes: List<String>,
): LeakResult {
    // Native objects held by collected Kotlin objects are freed on the main thread afterwards, hence the pauses.
    repeat(3) {
        delay(500.milliseconds)
        collectGarbage()
    }
    delay(1.seconds)
    val alive = released.filter { it.player.get() != null }
    return LeakResult(
        name = name,
        players = released.size,
        alive = alive.size,
        nativePlayers = natives.size,
        nativeAlive = natives.count { it.get() != null },
        registered = coordinator.players.value.size,
        problems = alive.take(5).map { "still reachable: ${it.name}" },
        notes = notes.toList(),
    )
}
