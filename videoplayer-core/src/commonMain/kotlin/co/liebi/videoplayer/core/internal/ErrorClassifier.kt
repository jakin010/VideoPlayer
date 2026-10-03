package co.liebi.videoplayer.core.internal

import co.liebi.videoplayer.core.ErrorCategory
import co.liebi.videoplayer.core.PlayerError

/** Builds normalized [PlayerError]s. Messages are fixed strings so no URL or credential can leak into them. */
internal object ErrorClassifier {

    fun of(category: ErrorCategory, platformDetail: String?): PlayerError = PlayerError(
        category = category,
        isRecoverable = category.isRecoverable,
        message = category.message,
        platformDetail = platformDetail,
    )

    fun fromHttpStatus(status: Int, platformDetail: String?): PlayerError {
        val category = when (status) {
            401, 403 -> ErrorCategory.AccessDenied
            404, 410 -> ErrorCategory.NotFound
            else -> ErrorCategory.Http
        }
        val recoverable = when (category) {
            ErrorCategory.Http -> status == 408 || status == 429 || status in 500..599
            else -> false
        }
        return PlayerError(
            category = category,
            isRecoverable = recoverable,
            message = "HTTP $status",
            httpStatus = status,
            platformDetail = platformDetail,
        )
    }

    fun isCredentialFailure(error: PlayerError): Boolean = error.httpStatus == 401 || error.httpStatus == 403

    private val ErrorCategory.isRecoverable: Boolean
        get() = when (this) {
            ErrorCategory.Network, ErrorCategory.Decoder, ErrorCategory.Unknown -> true
            ErrorCategory.Http, ErrorCategory.AccessDenied, ErrorCategory.NotFound, ErrorCategory.UnsupportedFormat -> false
        }

    private val ErrorCategory.message: String
        get() = when (this) {
            ErrorCategory.Network -> "Network error"
            ErrorCategory.Http -> "HTTP error"
            ErrorCategory.AccessDenied -> "Access denied"
            ErrorCategory.NotFound -> "Media not found"
            ErrorCategory.UnsupportedFormat -> "Unsupported format"
            ErrorCategory.Decoder -> "Decoder error"
            ErrorCategory.Unknown -> "Unknown error"
        }
}
