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
        // Android-compatible jaudiotagger fork
        maven("https://jitpack.io") {
            content { includeGroup("com.github.AdrienPoupa") }
        }
    }
}

rootProject.name = "SongFixer"
include(":app")
