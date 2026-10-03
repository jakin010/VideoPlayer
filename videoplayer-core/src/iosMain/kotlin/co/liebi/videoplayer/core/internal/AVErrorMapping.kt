package co.liebi.videoplayer.core.internal

import co.liebi.videoplayer.core.ErrorCategory
import co.liebi.videoplayer.core.PlayerError
import platform.AVFoundation.AVErrorContentIsNotAuthorized
import platform.AVFoundation.AVErrorContentIsProtected
import platform.AVFoundation.AVErrorContentIsUnavailable
import platform.AVFoundation.AVErrorDecodeFailed
import platform.AVFoundation.AVErrorDecoderNotFound
import platform.AVFoundation.AVErrorDecoderTemporarilyUnavailable
import platform.AVFoundation.AVErrorFailedToLoadMediaData
import platform.AVFoundation.AVErrorFailedToParse
import platform.AVFoundation.AVErrorFileFailedToParse
import platform.AVFoundation.AVErrorFileFormatNotRecognized
import platform.AVFoundation.AVErrorFormatUnsupported
import platform.AVFoundation.AVErrorIncompatibleAsset
import platform.AVFoundation.AVErrorInvalidSourceMedia
import platform.AVFoundation.AVErrorMediaServicesWereReset
import platform.AVFoundation.AVErrorNoSourceTrack
import platform.AVFoundation.AVErrorOutOfMemory
import platform.AVFoundation.AVErrorServerIncorrectlyConfigured
import platform.AVFoundation.AVErrorUndecodableMediaData
import platform.AVFoundation.AVFoundationErrorDomain
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemErrorLogEvent
import platform.AVFoundation.errorLog
import platform.Foundation.NSError
import platform.Foundation.NSURLErrorAppTransportSecurityRequiresSecureConnection
import platform.Foundation.NSURLErrorBadURL
import platform.Foundation.NSURLErrorCallIsActive
import platform.Foundation.NSURLErrorCannotConnectToHost
import platform.Foundation.NSURLErrorCannotFindHost
import platform.Foundation.NSURLErrorCannotLoadFromNetwork
import platform.Foundation.NSURLErrorDNSLookupFailed
import platform.Foundation.NSURLErrorDataNotAllowed
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorFileDoesNotExist
import platform.Foundation.NSURLErrorInternationalRoamingOff
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLErrorNoPermissionsToReadFile
import platform.Foundation.NSURLErrorNotConnectedToInternet
import platform.Foundation.NSURLErrorResourceUnavailable
import platform.Foundation.NSURLErrorSecureConnectionFailed
import platform.Foundation.NSURLErrorTimedOut
import platform.Foundation.NSURLErrorUnsupportedURL
import platform.Foundation.NSURLErrorUserAuthenticationRequired
import platform.Foundation.NSURLErrorUserCancelledAuthentication
import platform.Foundation.NSUnderlyingErrorKey

/**
 * Maps AVFoundation failures onto the common categories (§9). HTTP statuses come from the item's
 * error log, which is where AVPlayer reports failed HLS and progressive requests.
 */
internal fun NSError?.toPlayerError(item: AVPlayerItem?): PlayerError {
    val logEvent = item?.errorLog()?.events?.lastOrNull() as? AVPlayerItemErrorLogEvent
    val chain = generateSequence(this) { it.userInfo[NSUnderlyingErrorKey] as? NSError }.toList()
    val detail = buildList {
        chain.forEach { add("${it.domain} ${it.code}") }
        logEvent?.let { add("log ${it.errorDomain} ${it.errorStatusCode}") }
    }.joinToString("; ").ifEmpty { null }

    val httpStatus = logEvent?.let { httpStatusOf(it.errorDomain, it.errorStatusCode) }
        ?: chain.firstNotNullOfOrNull { httpStatusOf(it.domain, it.code) }
    if (httpStatus != null) return ErrorClassifier.fromHttpStatus(httpStatus, detail)

    val category = chain.firstNotNullOfOrNull { categoryOf(it.domain, it.code) } ?: ErrorCategory.Unknown
    return ErrorClassifier.of(category, detail)
}

private fun httpStatusOf(domain: String?, code: Long): Int? = when {
    code in 400L..599L -> code.toInt()
    domain == CoreMediaErrorDomain -> when (code) {
        -12938L -> 404 // "HTTP 404: File Not Found"
        -12660L -> 403 // "HTTP 403: Forbidden"
        else -> null
    }
    else -> null
}

private fun categoryOf(domain: String?, code: Long): ErrorCategory? = when (domain) {
    NSURLErrorDomain -> when (code) {
        NSURLErrorTimedOut,
        NSURLErrorCannotFindHost,
        NSURLErrorCannotConnectToHost,
        NSURLErrorNetworkConnectionLost,
        NSURLErrorDNSLookupFailed,
        NSURLErrorNotConnectedToInternet,
        NSURLErrorInternationalRoamingOff,
        NSURLErrorCallIsActive,
        NSURLErrorDataNotAllowed,
        NSURLErrorSecureConnectionFailed,
        NSURLErrorCannotLoadFromNetwork,
        -> ErrorCategory.Network

        NSURLErrorBadURL,
        NSURLErrorUnsupportedURL,
        NSURLErrorResourceUnavailable,
        NSURLErrorFileDoesNotExist,
        -> ErrorCategory.NotFound

        NSURLErrorUserCancelledAuthentication,
        NSURLErrorUserAuthenticationRequired,
        NSURLErrorNoPermissionsToReadFile,
        NSURLErrorAppTransportSecurityRequiresSecureConnection,
        -> ErrorCategory.AccessDenied

        else -> null
    }

    AVFoundationErrorDomain -> when (code) {
        AVErrorDecodeFailed,
        AVErrorMediaServicesWereReset,
        AVErrorDecoderTemporarilyUnavailable,
        AVErrorOutOfMemory,
        -> ErrorCategory.Decoder

        AVErrorFileFormatNotRecognized,
        AVErrorFileFailedToParse,
        AVErrorFailedToParse,
        AVErrorDecoderNotFound,
        AVErrorContentIsProtected,
        AVErrorContentIsNotAuthorized,
        AVErrorFormatUnsupported,
        AVErrorUndecodableMediaData,
        AVErrorIncompatibleAsset,
        AVErrorInvalidSourceMedia,
        AVErrorNoSourceTrack,
        -> ErrorCategory.UnsupportedFormat

        AVErrorContentIsUnavailable -> ErrorCategory.NotFound
        AVErrorFailedToLoadMediaData -> ErrorCategory.Network
        AVErrorServerIncorrectlyConfigured -> ErrorCategory.Http
        else -> null
    }

    else -> null
}

private const val CoreMediaErrorDomain = "CoreMediaErrorDomain"
