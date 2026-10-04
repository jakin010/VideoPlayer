package co.liebi.videoplayer.sample

import co.liebi.videoplayer.sample.checks.ChecksHooks
import leakcanary.AppWatcher

/** Debug builds: LeakCanary reports any player the checks released that stays reachable. */
fun installLeakWatcher() {
    ChecksHooks.watchReleased = { player, description -> AppWatcher.objectWatcher.expectWeaklyReachable(player, description) }
}
