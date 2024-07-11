plugins {
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.android.library)
  id("maven-publish")
  id("signing")
}

group = rootProject.group
version = rootProject.version

kotlin {
  androidTarget {
    publishAllLibraryVariants()
    compilations.all { kotlinOptions { jvmTarget = "17" } }
  }

  jvm()

  listOf(iosX64(), iosArm64(), iosSimulatorArm64(), macosX64(), macosArm64()).forEach {
    it.binaries.framework {
      baseName = "NSD-KT"
      isStatic = true
    }
  }

  sourceSets {
    commonMain.dependencies { implementation(libs.kotlin.coroutines) }

    commonTest.dependencies { implementation(kotlin("test")) }

    androidMain.dependencies {
      implementation(libs.android.startup)
      implementation(libs.androidx.appcompat)
    }

    jvmMain.dependencies { api(libs.jmdns) }

    appleMain.dependencies {}
  }
}

android {
  namespace = "com.appstractive.dnssd"
  compileSdk = 34

  defaultConfig { minSdk = 24 }
  sourceSets["main"].apply {
    manifest.srcFile("src/androidMain/AndroidManifest.xml")
    res.srcDirs("src/commonMain/resources")
    resources.srcDirs("src/commonMain/resources")
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

publishing {
  publications.all {
    this as MavenPublication

    pom {
      name.set(project.name)
      description.set("DNS-SD implementation for Kotlin Multiplatform")
      url.set("https://github.com/Appstractive/dns-sd-kt")

      scm {
        url.set("https://github.com/Appstractive/dns-sd-kt")
        connection.set("scm:git:https://github.com/Appstractive/dns-sd-kt.git")
        developerConnection.set("scm:git:https://github.com/Appstractive/dns-sd-kt.git")
        tag.set("HEAD")
      }

      issueManagement {
        system.set("GitHub Issues")
        url.set("https://github.com/Appstractive/dns-sd-kt/issues")
      }

      developers {
        developer {
          name.set("Andreas Schulz")
          email.set("dev@appstractive.com")
        }
      }

      licenses {
        license {
          name.set("The Apache Software License, Version 2.0")
          url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
          distribution.set("repo")
          comments.set("A business-friendly OSS license")
        }
      }
    }
  }
}

signing {
  val signingKey: String by rootProject.extra
  val signingPassword: String by rootProject.extra

  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications)
}
