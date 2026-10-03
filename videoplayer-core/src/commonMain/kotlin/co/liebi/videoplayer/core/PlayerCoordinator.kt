package co.liebi.videoplayer.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import co.liebi.videoplayer.core.internal.DefaultPlayerController
import co.liebi.videoplayer.core.internal.SystemEvents
import co.liebi.videoplayer.core.internal.SystemIntegration
import co.liebi.videoplayer.core.internal.createSystemIntegration
import co.liebi.videoplayer.core.internal.createPlatformEngine
import co.liebi.videoplayer.core.internal.logWarning
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

@Immutable
public data class CoordinatorConfig(
    /** Starting one player pauses every other playing player with [PauseReason.Coordinator]. */
    val singleActivePlayer: Boolean = false,
    /**
     * How many players may hold a native player at once, counting items kept prepared for instant switch-back.
     * Protects feeds from hardware decoder limits.
     */
    val maxActivePlayers: Int = 4,
    /**
     * Muted playback never interrupts other apps' audio (§13). iOS: the audio session stays ambient (mixing
     * with other apps and respecting the silent switch) unless an unmuted player is playing. Android: audio
     * focus is requested only while an unmuted player plays, and players pause when headphones disconnect.
     * Turn off if the app manages the audio session or audio focus itself.
     */
    val manageAudioSession: Boolean = true,
) {
    init {
        require(maxActivePlayers >= 1) { "maxActivePlayers must be at least 1" }
    }
}

/**
 * Owns everything that spans players (§14): the registry, coordinator-owned controllers, the single active
 * player policy, the native player cap, the fullscreen host, audio focus and interruptions, backgrounding,
 * and one merged event stream. Every controller belongs to exactly one coordinator; [Default] is used unless
 * another one is passed.
 *
 * All methods must be called on the main thread.
 */
public class PlayerCoordinator internal constructor(
    public val config: CoordinatorConfig,
    private val system: SystemIntegration,
    private val log: (String) -> Unit,
    private val createOwned: OwnedControllerFactory = OwnedControllerFactory(::createOwnedController),
) {
    public constructor(config: CoordinatorConfig = CoordinatorConfig()) : this(config, createSystemIntegration(), ::logWarning)

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Events of every registered player, merged, including registration changes. Hot, with no replay. */
    public val events: Flow<PlayerEvent> = _events.asSharedFlow()

    private val _players = MutableStateFlow<List<PlayerController>>(emptyList())

    /** The registered, unreleased players. */
    public val players: StateFlow<List<PlayerController>> = _players.asStateFlow()

    private val _fullscreenPlayer = MutableStateFlow<PlayerController?>(null)

    /** The player shown by the `FullscreenHost`, or `null` (§12). */
    public val fullscreenPlayer: StateFlow<PlayerController?> = _fullscreenPlayer.asStateFlow()

    private val controllers = mutableListOf<DefaultPlayerController>()
    private val owned = mutableMapOf<String, DefaultPlayerController>()
    private val lastUsed = mutableMapOf<DefaultPlayerController, Long>()
    private var useCount = 0L
    private var isAudible = false
    private var fullscreenHosts = 0

    // Held here because the platform keeps only a weak reference.
    private val systemEvents = object : SystemEvents {
        override fun onInterruptionBegan(includesMuted: Boolean) {
            controllers.filter { includesMuted || it.isAudible }.forEach { it.beginInterruption() }
        }

        override fun onInterruptionEnded(shouldResume: Boolean) {
            controllers.toList().forEach { it.endInterruption(shouldResume) }
        }

        override fun onAudioLost() {
            controllers.filter { it.isAudible }.forEach { it.loseAudio() }
        }

        override fun onBackground() {
            controllers.toList().forEach { it.enterBackground() }
        }

        override fun onForeground() {
            controllers.toList().forEach { it.enterForeground() }
        }
    }

    init {
        system.start(systemEvents)
        // Ambient from the start, so muted autoplay never interrupts other apps' audio (§13).
        if (config.manageAudioSession) system.onAudibleChanged(false)
    }

    /** Whether a `FullscreenHost` is placed for this coordinator. */
    internal val isFullscreenAvailable: Boolean
        get() = fullscreenHosts > 0

    /**
     * Registers a fullscreen host and returns the function that unregisters it. Called by `FullscreenHost`.
     * The player stays fullscreen while hosts come and go, so a host recreated on rotation picks it up again.
     */
    @InternalVideoPlayerApi
    public fun registerFullscreenHost(): () -> Unit {
        if (fullscreenHosts++ == 0) controllers.toList().forEach { it.setFullscreenAvailable(true) }
        var registered = true
        return {
            if (registered) {
                registered = false
                if (--fullscreenHosts == 0) controllers.toList().forEach { it.setFullscreenAvailable(false) }
            }
        }
    }

    /**
     * Gets or creates the coordinator-owned controller for [key]. It suspends when its last surface leaves
     * composition, and the coordinator releases it if nothing reattaches within
     * [LifecycleConfig.positionRetention]. [configuration] and [sourceRefresher] apply only when it is created.
     */
    public fun controllerFor(
        key: String,
        configuration: PlayerConfiguration = PlayerConfiguration(),
        sourceRefresher: SourceRefresher? = null,
    ): PlayerController = owned[key]?.takeUnless { it.isReleased }
        ?: createOwned.create(this, key, configuration, sourceRefresher).also { owned[key] = it }

    /** Releases every player that belongs to this coordinator. */
    public fun releaseAll() {
        controllers.toList().forEach { it.release() }
    }

    // region Called by controllers

    internal fun register(controller: DefaultPlayerController) {
        controllers += controller
        touch(controller)
        _players.value = controllers.toList()
        _events.tryEmit(controller.newEvent(PlayerEventType.PlayerRegistered))
    }

    internal fun unregister(controller: DefaultPlayerController) {
        if (!controllers.remove(controller)) return
        if (_fullscreenPlayer.value === controller) _fullscreenPlayer.value = null
        lastUsed.remove(controller)
        owned.entries.removeAll { it.value === controller }
        _players.value = controllers.toList()
        _events.tryEmit(controller.newEvent(PlayerEventType.PlayerUnregistered))
        onPlaybackChanged()
    }

    internal fun forward(event: PlayerEvent) {
        _events.tryEmit(event)
    }

    internal fun touch(controller: DefaultPlayerController) {
        lastUsed[controller] = ++useCount
    }

    /** A player's play intent was just set. */
    internal fun onPlayIntent(controller: DefaultPlayerController) {
        touch(controller)
        if (!config.singleActivePlayer) return
        // A player paused this way does not resume when the other one stops.
        controllers.filter { it !== controller && it.state.value.playWhenReady }.forEach { it.pauseForCoordinator() }
    }

    /**
     * Makes room before [requester] allocates a native player: first kept prepared items are dropped, then the
     * least recently used players that are not playing are suspended. If every player is playing, the cap is
     * exceeded and a warning is logged.
     */
    internal fun ensureCapacity(requester: DefaultPlayerController) {
        touch(requester)
        val max = config.maxActivePlayers
        fun hasRoom() = controllers.sumOf { it.nativePlayerCount } + 1 <= max
        if (hasRoom()) return

        for (controller in controllers.sortedBy { lastUsed[it] ?: 0L }) {
            controller.dropPreparedItems()
            if (hasRoom()) return
        }
        val idle = controllers
            .filter { it !== requester && it.nativePlayerCount > 0 && !it.state.value.playWhenReady }
            .sortedBy { lastUsed[it] ?: 0L }
        for (controller in idle) {
            controller.suspend()
            if (hasRoom()) return
        }
        log("maxActivePlayers ($max) exceeded: every other player is playing")
    }

    /** Recomputes the shared audio session after any player's playback state changed. */
    internal fun onPlaybackChanged() {
        val audible = controllers.any { it.isAudible }
        if (audible == isAudible) return
        isAudible = audible
        if (config.manageAudioSession) system.onAudibleChanged(audible)
    }

    /** Makes [controller] the fullscreen player; the previous one returns inline. `false` without a host. */
    internal fun showFullscreen(controller: DefaultPlayerController): Boolean {
        if (!isFullscreenAvailable) return false
        val previous = _fullscreenPlayer.value
        _fullscreenPlayer.value = controller
        if (previous !== controller) (previous as? DefaultPlayerController)?.leaveFullscreen()
        return true
    }

    internal fun hideFullscreen(controller: DefaultPlayerController) {
        if (_fullscreenPlayer.value === controller) _fullscreenPlayer.value = null
    }

    // Driven by the platform, and by tests.
    internal fun onInterruptionBegan(includesMuted: Boolean) = systemEvents.onInterruptionBegan(includesMuted)
    internal fun onInterruptionEnded(shouldResume: Boolean) = systemEvents.onInterruptionEnded(shouldResume)
    internal fun onAudioLost() = systemEvents.onAudioLost()
    internal fun onBackground() = systemEvents.onBackground()
    internal fun onForeground() = systemEvents.onForeground()

    // endregion

    public companion object {
        /** The coordinator used when none is passed. */
        public val Default: PlayerCoordinator by lazy { PlayerCoordinator() }
    }
}

internal fun interface OwnedControllerFactory {
    fun create(
        coordinator: PlayerCoordinator,
        key: String,
        configuration: PlayerConfiguration,
        sourceRefresher: SourceRefresher?,
    ): DefaultPlayerController
}

private fun createOwnedController(
    coordinator: PlayerCoordinator,
    key: String,
    configuration: PlayerConfiguration,
    sourceRefresher: SourceRefresher?,
) = DefaultPlayerController(
    configuration = configuration,
    sourceRefresher = sourceRefresher,
    engineFactory = { createPlatformEngine(configuration.buffering) },
    coordinator = coordinator,
    ownerKey = key,
)

/**
 * Gets or creates the coordinator-owned controller for [key] (§8). Recommended for lazy lists: the controller
 * and its positions outlive the list item, and the coordinator releases it once it has been off screen for
 * the retention period. The app does not release it.
 */
@Composable
public fun rememberPlayerController(
    key: String,
    coordinator: PlayerCoordinator = PlayerCoordinator.Default,
    configuration: PlayerConfiguration = PlayerConfiguration(),
    sourceRefresher: SourceRefresher? = null,
): PlayerController {
    // Bumped when the controller is released while still composed (after retention, or `releaseAll`),
    // so it is resolved again. Resolving only here, never in the effect, keeps a stale coordinator unused.
    var generation by remember(key, coordinator) { mutableIntStateOf(0) }
    val controller = remember(key, coordinator, generation) {
        coordinator.controllerFor(key, configuration, sourceRefresher)
    }
    LaunchedEffect(controller) {
        controller.state.first { it.lifecycle == PlayerLifecycle.Released }
        generation++
    }
    return controller
}
