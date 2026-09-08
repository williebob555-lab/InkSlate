import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin: no Android, no UI framework. Shared verbatim by the Android app and the desktop
// app so the document format cannot drift between them.
//
// Targets Java 17 bytecode because the Android app does, rather than requesting a toolchain -
// only a JDK 25 is installed here, and asking Gradle for a 17 toolchain sends it looking for a
// download that is not configured.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation("junit:junit:4.13.2")
}
