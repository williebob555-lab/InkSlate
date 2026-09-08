import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The document model, byte-for-byte the same classes the Android app uses.
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Full Apache PDFBox on the desktop: unlike the Android port it can render pages itself.
    implementation(libs.pdfbox.jvm)
}

compose.desktop {
    application {
        mainClass = "com.inkslate.desktop.MainKt"
        // Skiko loads its native renderer through System.load, which newer JDKs warn about on
        // every launch. The access is legitimate; this just stops the four-line warning.
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "InkSlate"
            // The release workflow passes -Pinkslate.version so the installer carries the same
            // number as the tag and the APK. MSI insists on a three-part version, so a tag like
            // v1.2 is padded out rather than rejected at the end of a long build.
            packageVersion = (findProperty("inkslate.version") as String? ?: "1.0.0")
                .split("-")[0]
                .split(".")
                .let { it + listOf("0", "0") }
                .take(3)
                .joinToString(".")
        }
    }
}
