enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "DNS-SD"

include(":sample:composeApp")

include(":lib", ":lib-java")

project(":lib").name = "dns-sd-kt"
project(":lib-java").name = "dns-sd-java"

pluginManagement {
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
