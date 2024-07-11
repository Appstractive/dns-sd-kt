import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  alias(libs.plugins.multiplatform)
  kotlin("native.cocoapods")
  alias(libs.plugins.compose)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.android.application)
}

kotlin {
  androidTarget { compilations.all { kotlinOptions { jvmTarget = "17" } } }

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
  compileSdk = 34

  defaultConfig {
    minSdk = 24
    targetSdk = 34

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
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
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
