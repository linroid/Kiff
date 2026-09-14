plugins {
  alias(libs.plugins.kotlin.multiplatform) apply false
  alias(libs.plugins.kotlin.jvm) apply false
}

group = "com.linroid.kiff"
version = "0.1.0"

subprojects {
  tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    compilerOptions {
      freeCompilerArgs.add("-Xexpect-actual-classes")
    }
  }
}
