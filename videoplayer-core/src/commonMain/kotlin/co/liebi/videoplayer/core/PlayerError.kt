package co.liebi.videoplayer.core

import androidx.compose.runtime.Immutable

/**
 * A platform error normalized into a [category].
 *
 * Recoverable errors are retried automatically before the player enters [PlaybackStatus.Error].
 * [message] never contains URLs or credentials.
 */
@Immutable
public data class PlayerError(
    val category: ErrorCategory,
    val isRecoverable: Boolean,
    val message: String,
    val httpStatus: Int? = null,
    /** Native error domain and code, for debugging only. */
    val platformDetail: String? = null,
)

public enum class ErrorCategory {
    /** Timeout, no connection, connection reset or DNS failure. Recoverable. */
    Network,

    /** HTTP 5xx, 408 and 429 are recoverable; other 4xx are not. */
    Http,

    /** HTTP 401 or 403 (after one credential refresh), or an unreadable local file. Not recoverable. */
    AccessDenied,

    /** HTTP 404, or a missing file or resource. Not recoverable. */
    NotFound,

    /** A container or codec the platform cannot play. Not recoverable. */
    UnsupportedFormat,

    /** The decoder failed to start or failed during playback, including decoder limits. Recoverable. */
    Decoder,

    /** Anything else. Recoverable. */
    Unknown,
}
