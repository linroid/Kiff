plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

dependencies {
  implementation(projects.library)
  implementation(libs.clikt)
}

application {
  applicationName = "kiff"
  mainClass.set("com.linroid.kiff.cli.MainKt")
  // Diffing APK-sized files keeps both inputs and the index in memory.
  applicationDefaultJvmArgs = listOf("-Xmx6g")
}
