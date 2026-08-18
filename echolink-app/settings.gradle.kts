pluginManagement {
    repositories {
        google()
        maven { url = uri("https://maven.aliyun.com/repository/central") } // CN mirror for flaky direct TLS
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://maven.aliyun.com/repository/central") } // CN mirror for flaky direct TLS
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "MoonTone"
include(":app")
