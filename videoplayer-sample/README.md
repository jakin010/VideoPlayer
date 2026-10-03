This is the sample app for LiebiVideoPlayer. It targets Android and iOS. It is part of the root Gradle build, so run Gradle commands from the repository root.

* [/iosApp](./iosApp/iosApp) contains the iOS application. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/androidApp](./androidApp/src) contains the Android application.

* [/shared](./shared/src) contains the sample's shared Compose Multiplatform code, which uses `videoplayer-core` and `videoplayer-ui`.
    - [commonMain](./shared/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.

### Running the apps

- Android app: `./gradlew :videoplayer-sample:androidApp:installDebug`
- iOS app: open [iosApp/iosApp.xcodeproj](./iosApp/iosApp.xcodeproj) in Xcode and run it from there.

### Running tests

- Android tests: `./gradlew :videoplayer-sample:shared:testAndroidHostTest`
- iOS tests: `./gradlew :videoplayer-sample:shared:iosSimulatorArm64Test`
