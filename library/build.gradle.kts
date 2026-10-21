import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import java.util.concurrent.Callable

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  // The base plugin, configured entirely below. The plain one also configures itself on apply from
  // Gradle properties such as SONATYPE_HOST, GROUP and POM_*, which a ~/.gradle/gradle.properties
  // set up for another project would turn into a broken build or a rewritten POM.
  alias(libs.plugins.vanniktech.publish)
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

  applyDefaultHierarchyTemplate {
    // The POSIX file calls Apple and Linux share and Windows lacks.
    common {
      group("posix") {
        withApple()
        withLinux()
      }
    }
  }

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

mavenPublishing {
  // Uploads a release as a Central Portal deployment, left for a person to publish from the portal
  // (or published straight away by `publishAndReleaseToMavenCentral`); a SNAPSHOT goes to the
  // Central snapshot repository instead. Saying so here keeps it that way when a global
  // mavenCentralAutomaticPublishing or SONATYPE_AUTOMATIC_RELEASE property says otherwise. The
  // credentials are the `mavenCentralUsername` and `mavenCentralPassword` Gradle properties, or the
  // same names prefixed `ORG_GRADLE_PROJECT_` in the environment: the plugin reads them itself, so
  // they cannot take the KIFF_ prefix.
  publishToMavenCentral(automaticRelease = false)
  // Signs every publication. The signing block below decides when a signature is required.
  signAllPublications()

  // There is no Kotlin documentation tool wired up here, so the javadoc jar Central wants is the
  // empty one rather than a generated one nobody would read; the documentation that matters is in
  // the source and in FORMAT.md.
  configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))

  // The Gradle project is called "library" because that is what it is within this build; the thing
  // people depend on is called kiff. Each target keeps its own suffix.
  coordinates(artifactId = "kiff")

  pom {
    name.set("Kiff")
    description.set(
      "Kotlin Multiplatform file diffing: turn two versions of a file into a patch, and " +
        "rebuild the second byte for byte from the first plus that patch."
    )
    url.set("https://github.com/linroid/kiff")
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
      url.set("https://github.com/linroid/kiff")
      connection.set("scm:git:https://github.com/linroid/kiff.git")
      developerConnection.set("scm:git:ssh://git@github.com/linroid/kiff.git")
    }
  }
}

signing {
  // The plugin also takes a key from its own signingInMemoryKey properties; a key given here wins.
  val key = signingSecret("signingKey")
  val password = signingSecret("signingPassword")
  // An unset CI secret arrives as an empty string, which is no key at all.
  if (!key.isNullOrBlank() && password != null) useInMemoryPgpKeys(key, password)
  // The plugin requires a signature on every non-SNAPSHOT publication, which would also stop a
  // clone without the key from publishing to mavenLocal. Keep the requirement for anything that
  // reaches Central, and let a local publish go unsigned when there is no key. The answer comes
  // from the task graph once it is built rather than from the command line, so an abbreviated task
  // name, or a task that depends on the upload, is held to it too. This replaces the plugin's own
  // requirement, so it has to come after signAllPublications().
  val snapshot = version.toString().endsWith("-SNAPSHOT")
  val graph = gradle.taskGraph
  setRequired(
    Callable { !snapshot && graph.allTasks.any { it.name.endsWith("ToMavenCentralRepository") } }
  )
}

/** A signing secret, from the environment first so CI needs no file. */
fun signingSecret(name: String): String? =
  providers.environmentVariable("KIFF_" + name.uppercase()).orNull
    ?: providers.gradleProperty(name).orNull
