pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MinimMusic"
include(":app")

// v5.10.0: Fermata (Minim's listening log, library and backups) is built from source out of the
// `fermata` git submodule, pinned to one commit. After cloning: git submodule update --init
// Gradle swaps the com.github.UniquelyGaurab.minim-fermata:* dependencies in app/ for these modules.
if (!file("fermata/settings.gradle.kts").exists()) {
    throw GradleException("The fermata submodule is missing. Run: git submodule update --init")
}
includeBuild("fermata")
