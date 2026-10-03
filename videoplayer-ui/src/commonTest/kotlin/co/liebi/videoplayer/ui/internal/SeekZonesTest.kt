package co.liebi.videoplayer.ui.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class SeekZonesTest {

    @Test
    fun defaultZonesAreFortyTwentyForty() {
        assertEquals(SeekZone.Back, seekZoneAt(x = 39f, width = 100f, deadZone = 0.2f))
        assertEquals(SeekZone.Middle, seekZoneAt(x = 41f, width = 100f, deadZone = 0.2f))
        assertEquals(SeekZone.Middle, seekZoneAt(x = 59f, width = 100f, deadZone = 0.2f))
        assertEquals(SeekZone.Forward, seekZoneAt(x = 61f, width = 100f, deadZone = 0.2f))
    }

    @Test
    fun noDeadZoneSplitsInHalf() {
        assertEquals(SeekZone.Back, seekZoneAt(x = 49f, width = 100f, deadZone = 0f))
        assertEquals(SeekZone.Forward, seekZoneAt(x = 51f, width = 100f, deadZone = 0f))
    }
}
