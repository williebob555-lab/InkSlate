import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    // AGP 9 has built-in Kotlin support, so no separate kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- release signing ---------------------------------------------------------------
//
// Every InkSlate build that is meant to replace an earlier one must be signed with the SAME
// key, because Android refuses to update an app with a differently-signed APK - it reports the
// unhelpful "App not installed" and stops. A debug keystore is generated per machine and per CI
// run, so relying on it would break the in-app updater on the very first update.
//
// The keystore is never committed. It is found either through a local keystore.properties
// (git-ignored) or, on CI, through environment variables fed from repository secrets.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(environment)

val keystorePath = signingValue("storeFile", "INKSLATE_KEYSTORE")
val hasReleaseKeystore = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.inkslate"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.inkslate"
        minSdk = 26
        targetSdk = 36
        // The release workflow derives both from the git tag, so a tag is the single place a
        // version number is decided. Locally they fall back to the values below.
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
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                // Still produces an installable APK for local testing, but one that can never
                // update an existing install. Loud, because silently doing this is the trap.
                logger.warn(
                    "InkSlate: no release keystore found - signing the release build with the " +
                        "debug key. This APK cannot update any previously installed InkSlate."
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    implementation(libs.coil.compose)
    implementation(libs.pdfbox.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
