plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.fabiantorrestech.lockmyphone_extended"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fabiantorrestech.lockmyphone_extended"
        minSdk = 30
        targetSdk = 37
        // versionCode = commits on this branch, so PC and CI builds agree and only go up (android-apps ADR 3).
        // Needs the full git history: CI checks out with fetch-depth 0.
        versionCode = providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
        }.standardOutput.asText.get().trim().toInt()
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // M9: R8 shrinks and optimizes, which makes Compose lists much smoother than debug.
            optimization {
                enable = true
            }
            // Sideloaded only, so the debug key is enough and Studio can install it directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    // Location triggers: geofencing and fused location (ADR 0004).
    implementation(libs.play.services.location)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}