import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// InkSheets for Windows and Linux: the InkSlate desktop app with a music library for a Home
// screen. The screens are the shared sources in /sheets-ui, compiled here against Compose
// Multiplatform and in the Android app's inksheets flavor against Jetpack Compose.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets.main { kotlin.srcDir(rootProject.file("sheets-ui/src/main/kotlin")) }
}

dependencies {
    implementation(project(":desktop"))
    implementation(project(":sheets-core"))
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.pdfbox.jvm)
    // Reading a MobileSheets library's database for import. Carries its own native library for
    // Windows and Linux, so nothing needs installing.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Decoders for paired recordings; Java Sound reads WAV and AIFF on its own.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    testImplementation("junit:junit:4.13.2")
}

val inkslateVersion = (findProperty("inkslate.version") as String? ?: "1.0.0")

/** Same folding as desktop/build.gradle.kts - see the long note on msiVersion there. */
fun packageVersionOf(version: String): String {
    val core = version.split("-")[0].split(".").let { it + listOf("0", "0") }.take(3)
    val major = core[0].toIntOrNull() ?: 1
    val minor = core[1].toIntOrNull() ?: 0
    val patch = core[2].toIntOrNull() ?: 0
    val build = Regex("""-test\.(\d+)""").find(version)?.groupValues?.get(1)?.toIntOrNull()
    val third = (patch.coerceAtMost(65) * 1000) + (build?.coerceIn(0, 998) ?: 999)
    return "$major.$minor.$third"
}

compose.desktop {
    application {
        mainClass = "com.inksheets.desktop.MainKt"
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            "-Dinkslate.version=$inkslateVersion",
            "-Dinkslate.appName=InkSheets"
        )
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Rpm)
            packageName = "InkSheets"
            packageVersion = packageVersionOf(inkslateVersion)
            // The microphone (tuner) and sound out (metronome).
            modules("java.desktop")

            linux {
                packageName = "inksheets"
                iconFile.set(rootProject.file("brand/inksheets-512.png"))
                shortcut = true
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                rpmLicenseType = "Proprietary"
            }
            windows {
                iconFile.set(rootProject.file("brand/inksheets.ico"))
                menu = true
                menuGroup = "InkSheets"
                shortcut = true
                dirChooser = true
                // Its own, so InkSheets installs beside InkSlate rather than replacing it.
                upgradeUuid = "3F0C2B7E-9A41-4C55-8E0D-6B2A7F1D9C43"
            }
        }
    }
}

dependencies {
    testImplementation(compose.desktop.uiTestJUnit4)
}

tasks.withType<Test>().configureEach {
    // Never the real app's settings folder: see desktop/build.gradle.kts.
    val sandbox = layout.buildDirectory.dir("test-appdata").get().asFile
    doFirst { sandbox.deleteRecursively(); sandbox.mkdirs() }
    environment("LOCALAPPDATA", sandbox.absolutePath)
    environment("XDG_DATA_HOME", sandbox.absolutePath)
    systemProperty("user.home", sandbox.absolutePath)
    System.getProperty("inksheets.msb")?.let {
        systemProperty("inksheets.msb", it)
        outputs.upToDateWhen { false }
    }
    testLogging.showStandardStreams = System.getProperty("inksheets.msb") != null
    System.getProperty("inksheets.shots")?.let {
        systemProperty("inksheets.shots", it)
        outputs.upToDateWhen { false }
    }
}
