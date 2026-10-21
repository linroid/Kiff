import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * The oldest JDK the published jar and the CLI run on. Without it the bytecode follows whatever JDK
 * happens to run the build, so a release built on 21 would refuse to load on 17.
 */
val minimumJdk = 17

// A release's version is its tag, which the release workflow passes as -Pversion=X.Y.Z and Gradle
// sets on every project. Any other build is a snapshot of no particular version.
allprojects {
  if (version.toString().let { it.isBlank() || it == Project.DEFAULT_VERSION }) {
    version = "0.0.0-SNAPSHOT"
  }
}

subprojects {
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      freeCompilerArgs.add("-Xexpect-actual-classes")
    }
  }
  tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
      jvmTarget.set(JvmTarget.fromTarget(minimumJdk.toString()))
      // The target alone still lets code call APIs newer than it; this compiles against the 17 API.
      freeCompilerArgs.add("-Xjdk-release=$minimumJdk")
    }
  }
  tasks.withType<JavaCompile>().configureEach {
    options.release.set(minimumJdk)
  }
}
