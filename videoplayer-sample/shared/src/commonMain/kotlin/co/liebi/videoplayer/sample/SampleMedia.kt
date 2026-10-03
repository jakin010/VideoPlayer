package co.liebi.videoplayer.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.MediaItem
import co.liebi.videoplayer.core.MediaSource
import kotlin.time.Duration

private val MuxHls = MediaItem(
    id = "hls",
    source = MediaSource.Url("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    title = "HLS VOD (Mux test stream)",
)

private val BigBuckBunnyMp4 = MediaItem(
    id = "mp4",
    source = MediaSource.Url("https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/1080/Big_Buck_Bunny_1080_10s_2MB.mp4"),
    title = "MP4 1080p, 10 s",
)

/** Items of the Player tab. */
val SampleItems = listOf(
    MuxHls,
    BigBuckBunnyMp4,
    MediaItem(
        id = "broken",
        source = MediaSource.Url("https://test-streams.mux.dev/does-not-exist.m3u8"),
        title = "Broken URL (404)",
    ),
)

/** Items of the Coordinator tab, one player each. */
val FeedItems = listOf(
    MuxHls,
    MediaItem(
        id = "tears",
        source = MediaSource.Url(
            "https://demo.unified-streaming.com/k8s/features/stable/video/tears-of-steel/tears-of-steel.ism/.m3u8",
        ),
        title = "Tears of Steel (Unified Streaming HLS)",
    ),
    BigBuckBunnyMp4,
    MediaItem(
        id = "bipbop",
        source = MediaSource.Url(
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
        ),
        title = "BipBop advanced (Apple fMP4 HLS)",
    ),
    MediaItem(
        // Plain HTTP: the sample allows cleartext for this host only (Android network config, iOS ATS).
        id = "http",
        source = MediaSource.Url("http://qthttp.apple.com.edgesuite.net/1010qwoeiuryfg/sl.m3u8"),
        title = "Apple legacy HLS over HTTP",
    ),
)

@Composable
fun Poster(title: String?) {
    Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.TopStart) {
        Text(title.orEmpty(), color = Color.White, modifier = Modifier.padding(12.dp))
    }
}

fun Duration.format(): String {
    val totalSeconds = inWholeSeconds
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
