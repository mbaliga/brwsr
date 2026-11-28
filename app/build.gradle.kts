plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.aarso.brwsr"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.aarso.brwsr"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from the environment only (CI secrets / your shell); never from the repo.
    // Without BRWSR_KEYSTORE_PATH the release build falls back to the debug key so it stays
    // installable for testing. That fallback is NOT uploadable to Play.
    val ksPath = System.getenv("BRWSR_KEYSTORE_PATH")
    signingConfigs {
        if (!ksPath.isNullOrBlank()) {
            create("upload") {
                storeFile = file(ksPath)
                storePassword = System.getenv("BRWSR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BRWSR_KEY_ALIAS")
                keyPassword = System.getenv("BRWSR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.crash.recovery)

    testImplementation(libs.junit)
}
