package co.liebi.videoplayer.ui.internal

import androidx.compose.runtime.Composable

/** Hides the status and navigation bars while composed, for fullscreen video (§12). */
@Composable
internal expect fun HideSystemBars()
