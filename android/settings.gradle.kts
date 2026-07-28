pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sms-spam-filter"

// Pure-JVM filtering engine: always buildable/testable, no Android SDK needed.
include(":filtercore")

// The Android app module needs the Android SDK. Only include it when an SDK
// is available so `gradle :filtercore:test` works in plain JVM environments.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (hasAndroidSdk) {
    include(":app")
}
