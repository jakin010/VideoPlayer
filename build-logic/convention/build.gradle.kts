plugins {
    `kotlin-dsl`
}

dependencies {
    // compileOnly: the plugins themselves are put on the classpath by the root build script
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = libs.plugins.liebi.kmpLibrary.get().pluginId
            implementationClass = "KmpLibraryConventionPlugin"
        }
    }
}
