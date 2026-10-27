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
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes ONLY from the environment (CI secrets or a keystore
    // kept outside the repo). Never commit a keystore: a key that was public
    // once can sign "updates" that install over this app forever. If no
    // keystore is provided the release build is left unsigned instead of
    // silently falling back to a debug key.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        ?.let { file(it) }
        ?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
