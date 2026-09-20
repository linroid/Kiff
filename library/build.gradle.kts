plugins {
  alias(libs.plugins.kotlin.multiplatform)
  `maven-publish`
  signing
}

kotlin {
  jvm()

  iosArm64()
  iosSimulatorArm64()

  macosArm64()
  macosX64()

  linuxX64()
  mingwX64()

  js {
    nodejs()
    browser()
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    commonMain.dependencies {
      implementation(libs.okio)
    }
    // okio's file system lives in a separate artifact on JS; everywhere else it is in okio core.
    jsMain.dependencies {
      implementation(libs.okio.nodefilesystem)
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

/**
 * Maven Central wants a javadoc artifact for every publication. There is no Kotlin documentation
 * tool wired up here, so this is the empty jar that satisfies the requirement honestly rather than
 * a generated one nobody would read; the documentation that matters is in the source and in
 * FORMAT.md.
 */
val javadocJar by tasks.registering(Jar::class) {
  archiveClassifier.set("javadoc")
}

publishing {
  publications.withType<MavenPublication>().configureEach {
    // The Gradle project is called "library" because that is what it is within this build; the
    // thing people depend on is called kiff. Each target keeps its own suffix.
    artifactId = artifactId.replace(project.name, "kiff")
    artifact(javadocJar)
    pom {
      name.set("Kiff")
      description.set(
        "Kotlin Multiplatform file diffing: turn two versions of a file into a patch, and " +
          "rebuild the second byte for byte from the first plus that patch."
      )
      url.set("https://github.com/linroid/Kiff")
      licenses {
        license {
          name.set("The Apache License, Version 2.0")
          url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
      }
      developers {
        developer {
          id.set("linroid")
          name.set("Lin Zhang")
          url.set("https://github.com/linroid")
        }
      }
      scm {
        url.set("https://github.com/linroid/Kiff")
        connection.set("scm:git:https://github.com/linroid/Kiff.git")
        developerConnection.set("scm:git:ssh://git@github.com/linroid/Kiff.git")
      }
    }
  }

  repositories {
    maven {
      name = "mavenCentral"
      // Snapshots go somewhere different, and publishing one to the release repository fails in a
      // way that is tedious to unpick, so the choice is made from the version rather than by hand.
      url = uri(
        if (version.toString().endsWith("SNAPSHOT")) {
          "https://central.sonatype.com/repository/maven-snapshots/"
        } else {
          "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/"
        }
      )
      credentials {
        username = publishProperty("centralUsername")
        password = publishProperty("centralPassword")
      }
    }
  }
}

signing {
  // Absent credentials the whole thing stays off, so a clone builds, tests and publishes to
  // mavenLocal without needing a key - and a real release fails loudly rather than shipping
  // unsigned, because Central rejects it.
  val key = publishProperty("signingKey")
  val password = publishProperty("signingPassword")
  if (key != null && password != null) {
    useInMemoryPgpKeys(key, password)
    sign(publishing.publications)
  }
}

// Signing produces the signatures as a separate task, which Gradle does not otherwise know the
// publishing tasks depend on.
tasks.withType<AbstractPublishToMaven>().configureEach {
  dependsOn(tasks.withType<Sign>())
}

/** A publishing secret, from the environment first so CI needs no file. */
fun publishProperty(name: String): String? =
  providers.environmentVariable("KIFF_" + name.uppercase()).orNull
    ?: providers.gradleProperty(name).orNull
