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
        versionCode = 1
        versionName = "0.1.0-phase1"
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
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}
