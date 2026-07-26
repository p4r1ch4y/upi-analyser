pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK declared by `jvmToolchain(...)` when the machine
    // does not already have a matching one installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SpendLens"

include(":app")
include(":core:model")
include(":core:parser")
include(":core:resolution")
include(":core:database")
include(":core:fusion")
