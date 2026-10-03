package co.liebi.videoplayer.ui.internal

import androidx.compose.runtime.Composable

/** Whether TalkBack or VoiceOver is on, updated when it changes. */
@Composable
internal expect fun rememberIsScreenReaderEnabled(): Boolean
