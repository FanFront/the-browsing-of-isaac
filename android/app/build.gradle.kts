plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fanfront.isaac"
    compileSdk = 36
    // Prefer the SDK 36 toolchain already present in the developer environment.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.fanfront.isaac"
        minSdk = 29
        // Keep Android 15 behavior until Android 16 target changes are tested.
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Upstream range requests require seekable (uncompressed) .bin APK entries.
    androidResources {
        noCompress += "bin"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
