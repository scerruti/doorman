pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        mavenLocal() // Tells Gradle to look on your Mac's "local shelf" for plugins
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal() // Tells Gradle to look on your Mac's "local shelf" for plugins
    }
}
rootProject.name = "doorman"
include(":shared")
include(":cli")
include(":android-mobile")
include(":android-automotive")
