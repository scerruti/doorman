import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
}

val secrets = Properties().also { props ->
    val f = rootProject.file("device-secrets.properties")
    if (f.exists()) props.load(f.inputStream())
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

        buildConfigField("String", "TARGET_MAC",      "\"${secrets["switchbot.mac.address"]          ?: ""}\"")
        buildConfigField("String", "ENCRYPTION_KEY",  "\"${secrets["switchbot.device.encryption.key"] ?: ""}\"")
        buildConfigField("String", "KEY_ID",          "\"${secrets["switchbot.device.key.id"]         ?: ""}\"")
        buildConfigField("int",    "TRAVEL_SECONDS",  "${secrets["switchbot.door.travel.seconds"]      ?: 15}")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}