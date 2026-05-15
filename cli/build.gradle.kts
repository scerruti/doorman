plugins {
    kotlin("jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

application {
    mainClass.set("com.otabi.doorman.macos.MainKt")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}