package co.liebi.videoplayer.sample

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.sample.checks.ChecksHooks
import co.liebi.videoplayer.sample.checks.ChecksScreen
import co.liebi.videoplayer.sample.checks.ChecksViewModel
import co.liebi.videoplayer.ui.FullscreenHost

private val Tabs = listOf("Player", "Coordinator", "Checks")

@Composable
fun App() {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        // Surface provides the matching content color for text in light and dark themes.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Launching with checks to run opens the Checks tab.
            var tab by rememberSaveable { mutableIntStateOf(if (ChecksHooks.autoRun != null) 2 else 0) }
            val coordinatorViewModel = viewModel { CoordinatorViewModel() }
            val checksViewModel = viewModel { ChecksViewModel() }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().safeContentPadding()) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tabs.forEachIndexed { index, title ->
                            Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                        }
                    }
                    when (tab) {
                        0 -> PlayerScreen(viewModel { PlayerViewModel() }.controller)
                        1 -> CoordinatorScreen(coordinatorViewModel)
                        else -> ChecksScreen(checksViewModel)
                    }
                }
                // One host per coordinator, above everything and outside the system bar padding.
                FullscreenHost(PlayerCoordinator.Default)
                FullscreenHost(coordinatorViewModel.coordinator)
                FullscreenHost(checksViewModel.coordinator)
            }
        }
    }
}
