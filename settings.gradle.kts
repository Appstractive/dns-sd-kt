enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "DNS-SD"

include(":sample:composeApp")

include(":lib")

project(":lib").name = "dns-sd-kt"

pluginManagement {
  includeBuild("build-logic")
  repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}
