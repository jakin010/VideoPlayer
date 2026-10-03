plugins {
    alias(libs.plugins.liebi.kmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    android {
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.videoplayerCore)
            implementation(libs.compose.animation)
            implementation(libs.compose.foundation)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.navigationevent.compose)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(projects.videoplayerTest)
        }
        // Compose UI tests run on the iOS simulator; Android host tests would need Robolectric.
        iosTest.dependencies {
            implementation(libs.compose.uiTest)
        }
    }
}

compose.resources {
    packageOfResClass = "co.liebi.videoplayer.ui.generated.resources"
}
