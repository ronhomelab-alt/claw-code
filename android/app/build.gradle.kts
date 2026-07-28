plugins {
    id("com.android.application") version "8.7.3"
    kotlin("android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.clawcode.smsfilter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clawcode.smsfilter"
        minSdk = 29 // Android 10: RoleManager for the default-SMS-app role
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.0"
    }

    // Sign every debug build with the same committed keystore so updates
    // install in place. CI used to generate a fresh key per build, which made
    // Android reject each new APK as a "different app" (App not installed).
    // This key signs personal debug builds only - not for release/Play use.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":filtercore"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
}
