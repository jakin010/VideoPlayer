package co.liebi.videoplayer.core.internal

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import co.liebi.videoplayer.core.ErrorCategory
import co.liebi.videoplayer.core.PlayerError

/** Maps Media3 error codes onto the common categories (§9). */
@OptIn(UnstableApi::class)
internal fun PlaybackException.toPlayerError(): PlayerError {
    val detail = "$errorCodeName ($errorCode)"
    val httpStatus = generateSequence<Throwable>(this) { it.cause }
        .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
        .firstOrNull()
        ?.responseCode
    if (httpStatus != null) return ErrorClassifier.fromHttpStatus(httpStatus, detail)

    val category = when (errorCode) {
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> ErrorCategory.Network

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> ErrorCategory.Http

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> ErrorCategory.NotFound

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        -> ErrorCategory.AccessDenied

        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> ErrorCategory.UnsupportedFormat

        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        -> ErrorCategory.Decoder

        else -> when (errorCode) {
            in AUDIO_TRACK_ERRORS, in VIDEO_FRAME_PROCESSING_ERRORS -> ErrorCategory.Decoder
            // No DRM support in v1.
            in DRM_ERRORS -> ErrorCategory.UnsupportedFormat
            else -> ErrorCategory.Unknown
        }
    }
    return ErrorClassifier.of(category, detail)
}

private val AUDIO_TRACK_ERRORS = 5000..5999
private val DRM_ERRORS = 6000..6999
private val VIDEO_FRAME_PROCESSING_ERRORS = 7000..7999
