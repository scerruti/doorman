plugins {
    kotlin("multiplatform")
    id("com.android.library")
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }
    jvm { // Supports your current CLI prototype
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }
    }
}

android {
    namespace = "com.otabi.doorman.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 28 // Required for AAOS target
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.otabi.switchbot:lib-switchbot:1.0-SNAPSHOT")
}