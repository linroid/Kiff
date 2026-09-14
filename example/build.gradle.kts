plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

group = "com.linroid.kiff"
version = "0.1.0"

dependencies {
  implementation(projects.library)
  implementation(libs.clikt)
}

application {
  mainClass.set("com.linroid.kiff.example.MainKt")
}
