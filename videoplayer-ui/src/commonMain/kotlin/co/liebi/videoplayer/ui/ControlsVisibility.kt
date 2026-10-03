package co.liebi.videoplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerState
import co.liebi.videoplayer.ui.internal.rememberIsScreenReaderEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Auto-hide state for controls that sit over the video (§11). Controls given this state (for example
 * `PlayerControls(visibility = ...)`) show and hide with it; controls without it are always visible.
 *
 * The controls hide [hideAfter] after the last interaction while playing. They are forced visible while
 * paused, ended, loading or in error, while scrubbing, and whenever a screen reader is on.
 * A hold-to-pause gesture does not force them visible.
 */
@Stable
public class ControlsVisibility internal constructor(private val hideAfter: Duration) {

    private var isRequested by mutableStateOf(true)
    private var interactionCount by mutableIntStateOf(0)

    /** Set from the player state and the screen reader. */
    internal var isForced by mutableStateOf(false)

    /** Set by the scrubber for the whole drag. */
    internal var isScrubbing by mutableStateOf(false)

    public val isVisible: Boolean
        get() = isRequested || isForced || isScrubbing

    public fun show() {
        isRequested = true
        interactionCount++
    }

    public fun hide() {
        isRequested = false
    }

    public fun toggle() {
        if (isVisible) hide() else show()
    }

    /** Restarts the auto-hide timer if the controls are visible. Hidden controls stay hidden. */
    public fun onInteraction() {
        if (isVisible) interactionCount++
    }

    internal suspend fun runAutoHide() {
        var wasHeld = false
        snapshotFlow { Triple(isRequested, isForced || isScrubbing, interactionCount) }.collectLatest { (requested, held, _) ->
            val released = wasHeld && !held
            wasHeld = held
            if (released && !requested) {
                // When playback resumes (or scrubbing ends), keep the controls up for a full period.
                // The write emits again, which starts the timer below.
                isRequested = true
            } else if (requested && !held) {
                delay(hideAfter)
                isRequested = false
            }
        }
    }
}

/** Creates auto-hide state for [controller]'s controls. */
@Composable
public fun rememberControlsVisibility(
    controller: PlayerController,
    hideAfter: Duration = 3.seconds,
): ControlsVisibility {
    val visibility = remember(controller, hideAfter) { ControlsVisibility(hideAfter) }
    val state by controller.state.collectAsState()
    val isScreenReaderOn = rememberIsScreenReaderEnabled()
    val isForced = state.forcesControlsVisible() || isScreenReaderOn
    SideEffect { visibility.isForced = isForced }
    LaunchedEffect(visibility) { visibility.runAutoHide() }
    return visibility
}

private fun PlayerState.forcesControlsVisible(): Boolean = when {
    status == PlaybackStatus.Ended || status == PlaybackStatus.Error -> true
    status == PlaybackStatus.Preparing || status == PlaybackStatus.Buffering || isSeeking -> true
    // Paused, except by a hold, which keeps the frame unobstructed.
    !playWhenReady && pauseReason != PauseReason.Hold -> true
    else -> false
}
