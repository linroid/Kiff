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

// The manifest `kiff release` writes names the build that wrote it, and `kiff --version` says which
// commit a snapshot was built from: every snapshot is 0.0.0-SNAPSHOT, while a release's tag already
// names its commit. Without git, as in a source tarball, there is no commit to name. Only a
// checkout's own .git counts, so a copy of the source inside another repository names none.
tasks.processResources {
  val version = project.version.toString()
  val commit = if (version.endsWith("-SNAPSHOT") && rootDir.resolve(".git").exists()) {
    // Read when the task runs rather than when the build is configured, so a new commit reruns
    // this task without invalidating the configuration cache.
    providers.exec {
      commandLine("git", "rev-parse", "--short", "HEAD")
      isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
  } else {
    provider { "" }
  }
  inputs.property("version", version)
  inputs.property("commit", commit)
  filesMatching("**/version.properties") {
    val hash = commit.get()
    expand("version" to version, "commit" to hash)
    if (hash.isEmpty()) filter { line -> line.takeUnless { it.startsWith("commit=") } }
  }
}
