import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The signing key comes from the build (GitHub Actions secrets), never from the repository:
// every release is signed with the same key, so updates install over each other.
val keystorePath: String? = System.getenv("LAUNCHER_KEYSTORE")

android {
    namespace = "io.github.bornaradusin.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.bornaradusin.launcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("LAUNCHER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LAUNCHER_KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "launcher"
                keyPassword = System.getenv("LAUNCHER_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
                    ?: System.getenv("LAUNCHER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe's extractor uses newer Java library parts than Android 8 has
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES", "/META-INF/INDEX.LIST")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Posters, backdrops and thumbnails
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")

    // Trailers: the player, and YouTube trailers' video
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")

    // Set up from your phone, and notifications from Home Assistant
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // The code to scan with the phone
    implementation("com.google.zxing:core:3.5.3")
}
