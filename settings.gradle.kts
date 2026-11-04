pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "brwsr"
include(":app")

// The constellation's shared libraries (git submodule, pinned). Only dev.aarso:crash-recovery
// is used. Gradle substitutes it from this included build — no Maven registry involved.
// AGP LOCKSTEP (D-Q): this build must pin the same AGP as shared-libraries (8.9.1).
includeBuild("shared-libraries")
