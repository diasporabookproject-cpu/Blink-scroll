plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.blinkscroll"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.blinkscroll"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Phones only: keeps the APK small (ML Kit ships native libraries).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // A fixed signing key (kept in this private repo) so that future versions
    // install over the previous one without uninstalling.
    signingConfigs {
        create("blink") {
            storeFile = file("blinkscroll.jks")
            storePassword = "blinkscroll-2026"
            keyAlias = "blinkscroll"
            keyPassword = "blinkscroll-2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("blink")
        }
        debug {
            signingConfig = signingConfigs.getByName("blink")
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
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")

    // On-device face detection with eye-open probabilities (bundled model, works offline).
    implementation("com.google.mlkit:face-detection:16.1.7")

    testImplementation("junit:junit:4.13.2")
}
