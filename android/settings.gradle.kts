// The Android installer: desktop's installer screens (../ui) and a native Rust core (native/) in one app.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "nuviozinstaller-android"
include(":app")
