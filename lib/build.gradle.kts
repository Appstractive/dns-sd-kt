import com.android.build.api.dsl.androidLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.multiplatform)
  alias(libs.plugins.android.library)
  id("dnssd.publication")
}

group = rootProject.group

version = rootProject.version

kotlin {
  androidLibrary {
    namespace = "com.appstractive.dnssd"
    compileSdk = 36
    minSdk = 23
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
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
      implementation(libs.android.startup)
      implementation(libs.androidx.appcompat)
    }

    jvmMain.dependencies { api(libs.jmdns) }

    appleMain.dependencies {}
  }
}
