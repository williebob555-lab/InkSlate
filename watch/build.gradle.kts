import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// InkSheets on a Wear OS watch (experimental): flick the wrist to turn the page. It talks only to
// the phone it is paired with, through the watch's own link (the Wear OS data layer), and that
// link joins an app only to the app of the SAME id signed with the SAME key on the phone - so it
// is `com.inksheets`, signed exactly as the phone app is (see app/build.gradle.kts).
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

val keystorePath = signingValue("storeFile", "INKSLATE_KEYSTORE")
val hasReleaseKeystore = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.inksheets.watch"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.inksheets"
        // Wear OS 3 and later: every Galaxy Watch from the Watch4 on.
        minSdk = 30
        targetSdk = 34
        versionCode = System.getenv("INKSLATE_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("INKSLATE_VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = signingValue("storePassword", "INKSLATE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "INKSLATE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "INKSLATE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Small enough to send to the watch quickly: unused library code taken out.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else {
                logger.warn("InkSheets watch: no release keystore - signed with the debug key, so it will only talk to a debug phone app.")
                signingConfigs.getByName("debug")
            }
        }
    }

    // The flick detector and the wire format: the same source the phone trains with, and only that
    // (the rest of sheets-core - the music reader's models - would make the watch app 12 MB).
    sourceSets {
        getByName("main") {
            kotlin.directories.add(rootProject.file("sheets-core/src/main/kotlin/com/inksheets/core/watch").path)
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation(libs.androidx.core.ktx)
}
