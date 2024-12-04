plugins {
  id("java-library")
  id("maven-publish")
  id("signing")
  alias(libs.plugins.dokka)
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

group = rootProject.group
version = rootProject.version

java {
  withJavadocJar()
  withSourcesJar()
}

publishing {
  publications {
    register<MavenPublication>("mavenJava") {
      from(components["java"])

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
}

val signingTasks = tasks.withType<Sign>()

tasks.withType<AbstractPublishToMaven>().configureEach { dependsOn(signingTasks) }

tasks {
  javadoc {
    options {
      (this as CoreJavadocOptions).addBooleanOption("Xdoclint:none", true)
    }
  }
}

signing {
  val signingKey: String by rootProject.extra
  val signingPassword: String by rootProject.extra

  useInMemoryPgpKeys(signingKey, signingPassword)
  sign(publishing.publications)
}
