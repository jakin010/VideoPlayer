package co.liebi.videoplayer.ui

import co.liebi.videoplayer.ui.internal.deviceTurnFromGravity
import co.liebi.videoplayer.ui.internal.isTurnedRight
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FullscreenRotationTest {

    @Test
    fun aLandscapeVideoOnAPortraitScreenTurnsClockwise() {
        assertEquals(90, autoRotation(screenIsLandscape = false, VideoShape.Landscape, deviceTurnedRight = null))
        assertEquals(90, autoRotation(screenIsLandscape = false, VideoShape.Landscape, deviceTurnedRight = false))
    }

    @Test
    fun theTurnFollowsTheWayTheDeviceIsHeld() {
        // Top of the device to the right: the content turns counterclockwise to stay upright.
        assertEquals(270, autoRotation(screenIsLandscape = false, VideoShape.Landscape, deviceTurnedRight = true))
    }

    @Test
    fun aVideoThatAlreadyMatchesTheScreenIsNotTurned() {
        assertEquals(0, autoRotation(screenIsLandscape = true, VideoShape.Landscape, deviceTurnedRight = true))
        assertEquals(0, autoRotation(screenIsLandscape = false, VideoShape.Portrait, deviceTurnedRight = false))
    }

    @Test
    fun aPortraitVideoOnALandscapeScreenTurns() {
        assertEquals(90, autoRotation(screenIsLandscape = true, VideoShape.Portrait, deviceTurnedRight = null))
    }

    @Test
    fun squareAndUnknownVideosAreNeverTurned() {
        assertEquals(0, autoRotation(screenIsLandscape = false, VideoShape.Square, deviceTurnedRight = true))
        assertEquals(0, autoRotation(screenIsLandscape = true, VideoShape.Square, deviceTurnedRight = null))
        assertEquals(0, autoRotation(screenIsLandscape = false, shape = null, deviceTurnedRight = true))
    }

    @Test
    fun videoShapes() {
        assertEquals(VideoShape.Landscape, videoShapeOf(IntSize(1920, 1080)))
        assertEquals(VideoShape.Landscape, videoShapeOf(IntSize(640, 480)))
        assertEquals(VideoShape.Portrait, videoShapeOf(IntSize(1080, 1920)))
        assertEquals(VideoShape.Square, videoShapeOf(IntSize(1080, 1080)))
        assertEquals(VideoShape.Square, videoShapeOf(IntSize(1050, 1000)))
        assertNull(videoShapeOf(null))
        assertNull(videoShapeOf(IntSize(0, 0)))
    }

    @Test
    fun rotateButtonsStepNinetyDegrees() {
        val rotation = FullscreenViewRotation()
        rotation.rotateRight()
        assertEquals(90, rotation.degrees)
        rotation.rotateLeft()
        rotation.rotateLeft()
        assertEquals(270, rotation.degrees)
        rotation.rotateRight()
        assertEquals(0, rotation.degrees)
    }

    @Test
    fun turnedRightIsRelativeToTheScreen() {
        assertTrue(isTurnedRight(deviceTurn = 270, screenTurn = 0))
        assertFalse(isTurnedRight(deviceTurn = 0, screenTurn = 0))
        assertFalse(isTurnedRight(deviceTurn = 90, screenTurn = 0), "turned left")
        assertFalse(isTurnedRight(deviceTurn = 180, screenTurn = 0), "upside down")
        // The screen already turned with the device: held as the screen is.
        assertFalse(isTurnedRight(deviceTurn = 270, screenTurn = 270))
        assertTrue(isTurnedRight(deviceTurn = 0, screenTurn = 90))
        // Readings between the quarters round to the nearest one.
        assertTrue(isTurnedRight(deviceTurn = 250, screenTurn = 0))
        assertFalse(isTurnedRight(deviceTurn = 350, screenTurn = 0))
    }

    @Test
    fun gravityGivesTheDevicesTurn() {
        assertEquals(0, deviceTurnFromGravity(x = 0.0, y = -1.0))
        assertEquals(90, deviceTurnFromGravity(x = -1.0, y = 0.0))
        assertEquals(180, deviceTurnFromGravity(x = 0.0, y = 1.0))
        assertEquals(270, deviceTurnFromGravity(x = 1.0, y = 0.0))
        assertNull(deviceTurnFromGravity(x = 0.1, y = -0.2), "lying flat")
    }
}
