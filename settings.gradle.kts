@file:Suppress("UnstableApiUsage")

pluginManagement {
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven {
      setUrl("https://jitpack.io")
      content { includeGroupByRegex("com\\.github\\.topjohnwu.*") }
    }
  }
}

rootProject.name = "KernelTuner"
include(":app")
