plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yczz.neteasepoweramp.downloader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yczz.neteasepoweramp.downloader"
        minSdk = 21
        targetSdk = 35
        versionCode = 7
        versionName = "2.2.1-tagfix"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    // Writes ID3 (mp3) / Vorbis (flac) tags so downloaded files carry lyrics + cover art.
    implementation("net.jthink:jaudiotagger:3.0.1")
}
