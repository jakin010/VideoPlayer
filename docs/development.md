# Development

## Requirements

- Android SDK: set `sdk.dir` in `local.properties`, or open the project in Android Studio, which sets it.
- Xcode, for the iOS sample and the iOS tests.
- A JDK for Gradle's daemon is provisioned automatically (`gradle/gradle-daemon-jvm.properties`).

## Building and testing

| Task | Command |
|---|---|
| Everything, with all tests | `./gradlew build` |
| All tests | `./gradlew allTests` |
| Core tests on the JVM / the iOS simulator | `./gradlew :videoplayer-core:testAndroidHostTest` / `./gradlew :videoplayer-core:iosSimulatorArm64Test` |
| UI tests (iOS simulator) | `./gradlew :videoplayer-ui:iosSimulatorArm64Test` |
| Android sample APK | `./gradlew :videoplayer-sample:androidApp:assembleDebug` |
| Publish to Maven Local | `./gradlew publishToMavenLocal` |

The iOS sample is `videoplayer-sample/iosApp/iosApp.xcodeproj`, scheme `videoplayer-sample.iosApp`. Open it in Xcode and run, or build from the command line:

```bash
xcodebuild -project videoplayer-sample/iosApp/iosApp.xcodeproj -scheme videoplayer-sample.iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build
```

The Xcode build runs Gradle to embed the shared Kotlin framework.

## Project conventions

- Library modules apply the `liebi.kmp.library` convention plugin from `build-logic`. It sets up the Android, `iosArm64` and `iosSimulatorArm64` targets, explicit API mode, JVM 11 bytecode, `maven-publish`, and an Android namespace from the module name (`videoplayer-core` → `co.liebi.videoplayer.core`).
- Dependency versions live in `gradle/libs.versions.toml`; group and version in `gradle.properties`.
- Explicit API mode is on: every public declaration needs a visibility modifier and should have KDoc.
- Behavior goes in common code, in `DefaultPlayerController` or the coordinator. Engines only report facts. See [Architecture](architecture.md).
- Strings go in `videoplayer-ui/src/commonMain/composeResources/values/strings.xml`, icons in `drawable/`. Use positional placeholders such as `%1$d`. A new string needs a translation in every `values-<language>` folder; `TranslationsTest` checks this. Icons are supplied with the project; don't add icon libraries.
- The repository is private: no license file and no binary-compatibility checks. Hosting (GitLab or GitHub) isn't decided yet, so there is no CI configuration.

## The sample app

| Tab | Shows |
|---|---|
| Player | One app-owned player with HLS, a landscape and a portrait (9:16) MP4 and a broken URL; aspect ratio switch, pan, zoom and rotation sliders with an out-of-bounds switch, the theme switch, debug buttons, live state and the event log |
| Coordinator | A feed of five streams with single-active, player cap and autoplay switches, player counts and the merged event log |
| Checks | The parity suite and the leak checks, see [Testing](testing.md#checks-on-the-real-engines) |

All sample players start muted. The test media are public streams and files (Mux, Unified Streaming, Apple, test-videos.co.uk, truefilesize.com); one of Apple's streams is plain HTTP, which the sample allows for that host only.

## Devices

### Android emulator

- **Address the emulator by serial** (`adb -s emulator-5554 …`) in every command. A bare `adb` command, `-d`, or Gradle's `installDebug` and `connected…` tasks act on whatever is connected, including a phone on wireless debugging. Install with `adb -s emulator-5554 install -r <apk>` instead.
- Start it with audio. Without audio (`-no-audio`), ExoPlayer's AAC decoder fails intermittently, which looks like constant small stalls.
- The `Pixel_6a` AVD has 2 GB of memory. Right after a cold boot it shows "Process system isn't responding" dialogs that swallow taps. Start it with `-memory 4096` and give it a minute after booting.
- The emulator's host-GPU renderer crashed once during the 100-item feed scroll. `-gpu swiftshader_indirect` avoids that, but then the software decoder can't get 1080p output buffers and the MP4 parity scenarios fail with `Decoder` errors. Run the parity suite with the default GPU.
- Don't answer simulated calls (`emu gsm accept`): it hung and crashed the emulator.
- After an emulator crash, the next boot waits on a "send crash report" dialog. Move `/tmp/android-<user>/emu-crash-*.db` aside to skip it.

```bash
emulator -avd Pixel_6a -no-boot-anim -no-snapshot-load -memory 4096
```

### iOS simulator

- Capture the app's console (for example the checks' output) with `xcrun simctl launch --console-pty <device> co.liebi.videoplayer.sample`.
- `heap`, `leaks` and Instruments' Leaks template can't scan simulator processes on our setup; see [Testing](testing.md#leak-checks).

### Sound

Keep players muted during manual tests unless the test is about mute itself. To test audio focus on the emulator silently, set the media volume to 0 first:

```bash
adb -s emulator-5554 shell cmd media_session volume --stream 3 --set 0
```

## Decisions

How the open decisions in §19 of the spec were settled:

| Decision | Outcome |
|---|---|
| iOS custom headers | The undocumented `AVURLAsset` option; it works on devices |
| Distribution | Private repository, to be hosted on GitLab; CI and publishing aren't set up yet |
| `maxActivePlayers` default | 4, checked on devices |
| Minimum OS versions | Android API 24, iOS 17 |
| Captions | No caption API; platform defaults apply |
| AirPlay | Off in v1 (`allowsExternalPlayback = false`) |
