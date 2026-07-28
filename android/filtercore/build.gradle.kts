plugins {
    kotlin("jvm") version "2.0.21"
}

// Target 17 for compatibility with the Android app module, but compile with
// whatever JDK (17+) is running Gradle — no separate toolchain needed.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
