package co.liebi.videoplayer.core

import androidx.compose.runtime.Immutable
import kotlin.time.Duration

/**
 * One playable item.
 *
 * @property id Stable identifier, unique within one player. Positions are remembered per ID.
 * @property startPosition Where playback starts when no position is remembered for this ID.
 * @property posterUrl Metadata for the app. The library loads no images.
 */
@Immutable
public data class MediaItem(
    val id: String,
    val source: MediaSource,
    val startPosition: Duration? = null,
    val title: String? = null,
    val description: String? = null,
    val posterUrl: String? = null,
)

/** Where an item's media comes from. Media the platform cannot decode fails with [ErrorCategory.UnsupportedFormat]. */
@Immutable
public sealed interface MediaSource {

    /**
     * Remote MP4 or HLS over HTTP(S).
     *
     * Headers and cookies apply to every request for the item, including HLS playlists, segments and keys.
     * Signed URLs need nothing else. Credentials never appear in logs, events or [toString].
     */
    public data class Url(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val cookies: List<Cookie> = emptyList(),
    ) : MediaSource {
        override fun toString(): String =
            "Url(url=${url.substringBefore('?')}, headers=${headers.keys}, cookies=${cookies.map { it.name }})"
    }

    /** A file on the device, by absolute path. */
    public data class File(val path: String) : MediaSource

    /**
     * A Compose resource, passed as the URI that the generated resource accessor returns,
     * for example `MediaSource.Resource(Res.getUri("files/intro.mp4"))`.
     */
    public data class Resource(val uri: String) : MediaSource
}

/** A cookie sent with every request for a [MediaSource.Url]. */
@Immutable
public data class Cookie(
    val name: String,
    val value: String,
    /** Defaults to the host of the item's URL. */
    val domain: String? = null,
    val path: String = "/",
    val isSecure: Boolean = false,
) {
    override fun toString(): String = "Cookie(name=$name, domain=$domain, path=$path, isSecure=$isSecure)"
}
