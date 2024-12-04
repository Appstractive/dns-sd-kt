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

val javadocJar by
tasks.registering(Jar::class) {
  archiveClassifier.set("javadoc")
  from(tasks.dokkaHtml)
}

publishing {
  publications {
    register<MavenPublication>("mavenJava") {
      from(components["java"])
      artifact(javadocJar)
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
