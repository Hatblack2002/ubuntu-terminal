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
        // Note: JitPack was previously declared here for the Termux
        // terminal-emulator/view JARs. It has been removed because
        // Termux must not be a dependency of this project (per the
        // separation principle). The application's terminal renderer
        // is implemented from scratch in ui/terminal/.
    }
}

rootProject.name = "UbuntuTerminal"
include(":app")
