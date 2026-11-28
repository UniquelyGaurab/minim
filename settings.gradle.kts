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
//
// Fallback for copies without the submodule (for example Google AI Studio): Fermata is fetched
// from JitPack, built from the same pinned commit. Keep fermataFallback equal to the submodule's
// commit (the safety check warns when they differ). Real builds and GitHub Actions use the submodule.
val fermataFallback = "b61134df49"
if (file("fermata/settings.gradle.kts").exists()) {
    includeBuild("fermata")
} else {
    logger.warn(
        "The fermata submodule is missing, so Fermata $fermataFallback comes from JitPack. " +
            "For a normal build run: git submodule update --init"
    )
    gradle.beforeProject {
        configurations.configureEach {
            resolutionStrategy.eachDependency {
                if (requested.group == "com.github.UniquelyGaurab.minim-fermata") {
                    useVersion(fermataFallback)
                }
            }
        }
    }
}
