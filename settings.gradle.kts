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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Only Google Maven and Maven Central are used.
        // Per separation principle: NO JitPack (no Termux, no unofficial
        // sources). The terminal renderer is implemented from scratch.
    }
}

rootProject.name = "UbuntuTerminal"
include(":app")
