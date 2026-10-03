plugins {
    alias(libs.plugins.liebi.kmpLibrary)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.videoplayerCore)
        }
    }
}
