# Audio and system integration

The coordinator handles the app-wide side of playback: audio session and focus, interruptions, the app going to the background, and keeping the screen awake. Turn the audio part off with `CoordinatorConfig(manageAudioSession = false)` if your app manages it itself.

## Muted playback never interrupts other apps

Muted autoplay that stops the user's music is the most common feed-video bug, so this is a hard rule.

| Situation | Android | iOS |
|---|---|---|
| Nothing audible playing (muted, volume 0 or paused) | No audio focus held | Session category ambient: mixes with other audio, respects the silent switch |
| An unmuted player playing | Audio focus requested (media usage) | Category playback, session active |

"Playing" here means play intent is set, so short stalls don't flip the session back and forth.

## Interruptions

| Event | Behavior |
|---|---|
| Temporary interruption (call, Siri, alarm, another app's transient focus) | Pause with `PauseReason.Interruption`. When it ends, resume only if the player was playing and the system allows it (iOS `shouldResume`, Android focus regained). |
| Another app asks to duck | Keep playing; the system lowers the volume |
| Permanent focus loss (another media app starts) | Pause with `Interruption`, no automatic resume |
| Headphones or Bluetooth disconnected | Pause with `Interruption`, no automatic resume |

- `state.isAudioInterrupted` is true while a temporary interruption is active.
- A user pause during an interruption wins: the end of the interruption won't resume.
- `play()` during an interruption takes over and clears `isAudioInterrupted`.
- On iOS an interruption stops the whole audio session, so muted players pause too. On Android only audible players are affected, because muted players hold no focus.

## Background

Moving the app to the background pauses every playing player with `PauseReason.Background`. With `LifecycleConfig(resumeAfterBackground = true)`, a player resumes on return if `Background` is still its pause reason; a user pause in between wins. Playing in the background is planned for a later version.

Android uses the process lifecycle, so rotation and other configuration changes don't count as backgrounding.

## Keeping the screen awake

With `keepScreenAwakeWhilePlaying` (the default), the screen stays on while a player is playing on a surface and times out normally otherwise.

- Android: `keepScreenOn` on the surface's view, cleared when it detaches. Several surfaces in one window are counted.
- iOS: the idle timer is app-wide, so requests are counted and the app's previous value is restored when the last player stops.

## Several coordinators

The iOS audio session and the Android audio focus are app-wide, so every coordinator votes:

- iOS: the session switches to playback while any coordinator has an unmuted player playing.
- Android: the app holds one focus request while any coordinator has an unmuted player playing. There is only one because two requests from the same app take focus from each other.
- Interruptions and backgrounding reach the players of every coordinator.

The parity scenario `two-coordinators-audible` checks this on both platforms.

## Testing on emulators

The interruption logic is unit-tested in common code. On devices, keep in mind:

- The Android emulator crashed when answering a simulated call, and a silently ringing call takes no audio focus, so calls can't be tested there.
- The iOS simulator can't simulate calls or route changes.
- Unplugging headphones can't be faked on the emulator either: the broadcast is system-only.
