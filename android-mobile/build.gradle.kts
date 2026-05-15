plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.otabi.doorman"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.otabi.doorman"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}