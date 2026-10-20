plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  application
}

dependencies {
  implementation(projects.library)
  implementation(libs.clikt)
  // The CLI's alone: the library itself stays on okio, so an app taking it takes nothing else.
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlin.test)
}

application {
  applicationName = "kiff"
  mainClass.set("com.linroid.kiff.cli.MainKt")
  // Diffing APK-sized files keeps both inputs and the index in memory.
  applicationDefaultJvmArgs = listOf("-Xmx6g")
}

// The manifest `kiff release` writes names the build that wrote it.
tasks.processResources {
  val version = project.version.toString()
  inputs.property("version", version)
  filesMatching("**/version.properties") {
    expand("version" to version)
  }
}
