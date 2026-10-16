rootProject.name = "Turbotube"

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
        // NewPipeExtractor and its nanojson fork are only published on JitPack
        maven(url = "https://jitpack.io")
    }
}

include(":core")
include(":app")
