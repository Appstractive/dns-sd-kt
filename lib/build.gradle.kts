import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.android.library)
  id("maven-publish")
  id("signing")
  alias(libs.plugins.dokka)
}

group = rootProject.group

version = rootProject.version

kotlin {
  androidTarget {
    publishAllLibraryVariants()
    compilerOptions {
      jvmTarget.set(JvmTarget.JVM_1_8)
    }
  }

  jvm()

  listOf(
          iosX64(),
          iosArm64(),
          iosSimulatorArm64(),
          macosX64(),
          macosArm64(),
          tvosX64(),
          tvosArm64(),
          tvosSimulatorArm64(),
      )
      .forEach {
        it.binaries.framework {
          baseName = "DNS-SD-KT"
          isStatic = true
        }
      }

  sourceSets {
    commonMain.dependencies { implementation(libs.kotlin.coroutines) }

    commonTest.dependencies { implementation(kotlin("test")) }

    androidMain.dependencies {
      implementation(projects.dnsSdJava)
      implementation(libs.android.startup)
      implementation(libs.androidx.appcompat)
    }

    jvmMain.dependencies { api(libs.jmdns) }

    appleMain.dependencies {}
  }
}

android {
  namespace = "com.appstractive.dnssd"
  compileSdk = 35

  defaultConfig {
    minSdk = 21
    consumerProguardFiles.add(file("consumer-rules.pro"))
  }
  sourceSets["main"].apply {
    manifest.srcFile("src/androidMain/AndroidManifest.xml")
    res.srcDirs("src/commonMain/resources")
    resources.srcDirs("src/commonMain/resources")
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true

    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
}

dependencies {
  coreLibraryDesugaring(libs.desugaring)
}

val javadocJar by
    tasks.registering(Jar::class) {
      archiveClassifier.set("javadoc")
      from(tasks.dokkaHtml)
    }

publishing {
  publications.withType<MavenPublication> {
    artifact(javadocJar)

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

val signingTasks = tasks.withType<Sign>()

tasks.withType<AbstractPublishToMaven>().configureEach { dependsOn(signingTasks) }

signing {
  val signingKey: String by rootProject.extra
  val signingPassword: String by rootProject.extra

  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications)
}
