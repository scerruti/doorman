plugins {
    kotlin("multiplatform") version "1.9.22"
    id("com.android.library") version "8.2.2"
}

kotlin {
    androidTarget {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }
    jvm {
        compilations.all { kotlinOptions.jvmTarget = "17" }
    }

    sourceSets {
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.otabi.doorman.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 28
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
