import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same signing key as the first Audio Converter, so Strata keeps trusting it (it pins plugins by signature).
// Create keystore.properties next to settings.gradle.kts:
//   storeFile=my-release-key.jks   storePassword=...   keyAlias=...   keyPassword=...
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "dev.cluvex.strata.audioconverter"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "dev.cluvex.strata.audioconverter"
        minSdk = 24
        targetSdk = 36
        // Must be higher than the installed 1.0.0 build so it updates in place; raise it if Android refuses.
        versionCode = 10
        versionName = "1.1.0"
        // Only arm64-v8a is published as a prebuilt library.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // FFmpegKit, community-maintained continuation of arthenica/ffmpeg-kit (same com.arthenica.ffmpegkit API).
    // The "audio" build has libmp3lame, libopus and libvorbis; AAC and FLAC are FFmpeg's own encoders.
    // Other lines: 7.1.6 and 8.1.7. smart-exception-java comes in transitively.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:6.0.3")

    testImplementation(libs.junit)
}
