plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.yczz.neteasepoweramp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yczz.neteasepoweramp"
        minSdk = 21
        targetSdk = 35
        versionCode = 25
        versionName = "1.0.4-relevance"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    // Official Poweramp API as a source module, same integration the official provider example uses.
    implementation(project(":poweramp_api_lib"))
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.ktx)
    // Phase 2: NetEase search over HTTP (OkHttp only - JSON parsed with built-in org.json) and Room cache.
    // Phase 3: coroutines for the playback resolver/streaming layer.
    implementation(libs.okhttp)
    // QR bitmap rendering for the NetEase scan-to-login flow.
    implementation(libs.zxing.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.coroutines.android)
    ksp(libs.room.compiler)
    // On-device provider contract tests (run as the app uid, so they can access the exported
    // DocumentsProvider directly - adb shell cannot, it lacks MANAGE_DOCUMENTS by design).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
