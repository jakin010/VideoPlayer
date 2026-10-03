package co.liebi.videoplayer.core.internal

import co.liebi.videoplayer.core.ErrorCategory
import co.liebi.videoplayer.core.MediaSource
import co.liebi.videoplayer.core.Cookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BufferGateTest {

    @Test
    fun waitsForTheThreshold() {
        assertFalse(BufferGate.isSatisfied(bufferedAhead = 1.seconds, remaining = 60.seconds, threshold = 2.5.seconds))
        assertTrue(BufferGate.isSatisfied(bufferedAhead = 2.5.seconds, remaining = 60.seconds, threshold = 2.5.seconds))
    }

    @Test
    fun usesTheRemainingDurationWhenLessMediaIsLeft() {
        assertTrue(BufferGate.isSatisfied(bufferedAhead = 1.seconds, remaining = 1.seconds, threshold = 5.seconds))
        assertFalse(BufferGate.isSatisfied(bufferedAhead = 500.milliseconds, remaining = 1.seconds, threshold = 5.seconds))
    }

    @Test
    fun unknownRemainingUsesTheThreshold() {
        assertFalse(BufferGate.isSatisfied(bufferedAhead = 4.seconds, remaining = null, threshold = 5.seconds))
    }
}

class ErrorClassifierTest {

    @Test
    fun classifiesHttpStatuses() {
        assertEquals(ErrorCategory.AccessDenied, ErrorClassifier.fromHttpStatus(401, null).category)
        assertEquals(ErrorCategory.AccessDenied, ErrorClassifier.fromHttpStatus(403, null).category)
        assertEquals(ErrorCategory.NotFound, ErrorClassifier.fromHttpStatus(404, null).category)
        assertFalse(ErrorClassifier.fromHttpStatus(400, null).isRecoverable)
        listOf(408, 429, 500, 503).forEach { status ->
            val error = ErrorClassifier.fromHttpStatus(status, null)
            assertEquals(ErrorCategory.Http, error.category)
            assertTrue(error.isRecoverable, "HTTP $status is recoverable")
        }
    }

    @Test
    fun credentialsNeverAppearInToString() {
        val source = MediaSource.Url(
            url = "https://cdn.example.com/video.m3u8?signature=secret",
            headers = mapOf("Authorization" to "Bearer secret"),
            cookies = listOf(Cookie("session", "secret")),
        )
        assertFalse("secret" in source.toString())
    }
}
