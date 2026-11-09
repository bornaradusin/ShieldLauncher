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
        // NewPipe's extractor (YouTube trailers)
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "ShieldLauncher"
include(":app")
