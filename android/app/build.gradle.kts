plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.fanfront.isaac"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.fanfront.isaac"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
}
