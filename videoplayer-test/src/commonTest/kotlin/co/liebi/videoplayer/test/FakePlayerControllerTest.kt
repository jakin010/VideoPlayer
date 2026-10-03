package co.liebi.videoplayer.test

import co.liebi.videoplayer.core.PauseReason
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FakePlayerControllerTest {

    @Test
    fun recordsCommandsAndUpdatesState() {
        val controller = FakePlayerController(PlayerState(status = PlaybackStatus.Ready))

        controller.play()
        assertTrue(controller.state.value.isPlaying)
        controller.seekTo(5.seconds)
        controller.pause()

        assertFalse(controller.state.value.playWhenReady)
        assertEquals(PauseReason.User, controller.state.value.pauseReason)
        assertEquals(5.seconds, controller.progress.value.position)
        assertEquals(
            listOf(FakePlayerController.Call.Play, FakePlayerController.Call.SeekTo(5.seconds), FakePlayerController.Call.Pause),
            controller.calls,
        )
    }
}
