plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Every automatic build gets the next number, so a new APK installs over the previous one.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.tvplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tvplayer"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        // The Shield (and other Android TV boxes) are ARM; leaving out other processors keeps the
        // backup player's built-in VLC engine from making the app much bigger.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A fixed key kept in the repository. Without it each build machine would sign with its own
        // throwaway key and Android would refuse to update an installed copy.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // The build that's published: not "debuggable", so Android runs it at full speed (a debug build
        // runs the app's code, and especially its screens, several times slower), with the startup
        // profile below. Signed with the same key as before, so it installs over the copy already there.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Store native libraries compressed: the backup player's VLC engine is large, and this roughly
    // halves the download. Android unpacks them once when the app is installed.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // The screenshot test runs the real screens on the build machine (Robolectric) and saves pictures.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "3g"
                // A fresh JVM per test, so each starts with an empty database and settings store.
                it.forkEvery = 1
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Draw like a real GPU so faded artwork and other layered effects come out right.
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
            }
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.extractor)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    // Sound formats some TV boxes can't decode themselves (Dolby Digital, DTS, MPEG layer 2): Google TV.
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.coil.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material.icons.core)
    implementation(libs.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.libvlc)

    // Installs the startup profile (src/main/baseline-prof.txt, plus the ones Compose and the other
    // libraries bring) when Skyfall is installed from an APK, so Android compiles that code ahead of time.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
