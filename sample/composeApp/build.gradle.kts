import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.multiplatform)
  kotlin("native.cocoapods")
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.android.application)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.add("-opt-in=kotlin.uuid.ExperimentalUuidApi")
  }

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
      )
      .forEach {
        it.binaries.framework {
          baseName = "ComposeApp"
          isStatic = true
        }
      }

  cocoapods {
    version = "1.0"
    summary = "app"
    homepage = "not published"
    ios.deploymentTarget = "14.1"
    podfile = project.file("../iosApp/Podfile")
  }

  sourceSets {
    all { languageSettings { optIn("org.jetbrains.compose.resources.ExperimentalResourceApi") } }
    commonMain.dependencies {
      implementation(compose.runtime)
      implementation(compose.foundation)
      implementation(compose.components.resources)
      implementation(compose.material3)

      implementation(projects.dnsSdKt)
    }

    commonTest.dependencies { implementation(kotlin("test")) }

    androidMain.dependencies {
      implementation(libs.androidx.appcompat)
      implementation(libs.androidx.activityCompose)
    }

    jvmMain.dependencies {
      implementation(compose.desktop.common)
      implementation(compose.desktop.currentOs)
    }

    iosMain.dependencies {}
  }
}

android {
  namespace = "com.appstractive.dnssd"
  compileSdk = 35

  defaultConfig {
    minSdk = 21
    targetSdk = 35

    applicationId = "com.appstractive.dnssd.androidApp"
    versionCode = 1
    versionName = "1.0.0"
  }
  sourceSets["main"].apply {
    manifest.srcFile("src/androidMain/AndroidManifest.xml")
    res.srcDirs("src/androidMain/resources")
    resources.srcDirs("src/commonMain/resources")
  }
  compileOptions {
    isCoreLibraryDesugaringEnabled = true

    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
  buildFeatures { compose = true }
}

dependencies {
  coreLibraryDesugaring(libs.desugaring)
}

compose.desktop {
  application {
    mainClass = "MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      packageName = "com.appstractive.dnssd.desktopApp"
      packageVersion = "1.0.0"
    }
  }
}
