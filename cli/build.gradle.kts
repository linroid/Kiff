import org.gradle.api.distribution.DistributionContainer
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.konan.target.Family

plugins {
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
}

// The manifest `kiff release` writes names the build that wrote it, and `kiff --version` says which
// commit a snapshot was built from: every snapshot is 0.0.0-SNAPSHOT, while a release's tag already
// names its commit. Without git, as in a source tarball, there is no commit to name. Only a
// checkout's own .git counts, so a copy of the source inside another repository names none.
val buildInfo = tasks.register<BuildInfoTask>("buildInfo") {
  val version = project.version.toString()
  this.version.set(version)
  commit.set(
    if (version.endsWith("-SNAPSHOT") && rootDir.resolve(".git").exists()) {
      // Read when the task runs rather than when the build is configured, so a new commit reruns
      // this task without invalidating the configuration cache.
      providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
      }.standardOutput.asText.map { it.trim() }
    } else {
      provider { "" }
    }
  )
  license.set(rootProject.layout.projectDirectory.file("LICENSE"))
  notices.set(layout.projectDirectory.file("third-party-notices.txt"))
  outputDirectory.set(layout.buildDirectory.dir("generated/buildInfo"))
}

kotlin {
  // The JVM build is the zip that runs wherever Java 17 does, and the fastest to test.
  jvm {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    binaries {
      executable {
        applicationName = "kiff"
        mainClass.set("com.linroid.kiff.cli.MainKt")
        // Diffing APK-sized files keeps both inputs and the index in memory.
        applicationDefaultJvmArgs = listOf("-Xmx6g")
      }
    }
  }

  // One executable each, with nothing else to install.
  listOf(linuxX64(), linuxArm64(), macosArm64(), macosX64(), mingwX64()).forEach { target ->
    target.binaries.executable {
      baseName = "kiff"
      entryPoint = "com.linroid.kiff.cli.main"
      // Only the system libraries kiff calls into: Kotlin/Native also asks for libcrypt.so.1 and
      // others it does not use, which some distributions no longer install.
      if (target.konanTarget.family == Family.LINUX) {
        linkerOpts("--as-needed")
        // A release ships without its symbol table, a quarter of the file. The Apple linker and
        // the Windows one ignore this, so the release workflow strips those executables itself.
        if (buildType == NativeBuildType.RELEASE) linkerOpts("-s")
      }
    }
  }

  applyDefaultHierarchyTemplate {
    // Running another program: exec on Apple and Linux, CreateProcess on Windows.
    common {
      group("native") {
        group("posix") {
          withApple()
          withLinux()
        }
      }
    }
  }

  sourceSets {
    commonMain {
      kotlin.srcDir(buildInfo.map { it.outputDirectory })
      dependencies {
        implementation(projects.library)
        implementation(libs.clikt)
        implementation(libs.okio)
        // The CLI's alone: the library itself stays on okio, so an app taking it takes nothing
        // else.
        implementation(libs.kotlinx.serialization.json)
      }
    }
    commonTest.dependencies {
      implementation(libs.kotlin.test)
    }
  }
}

/**
 * Writes what a build knows about itself as Kotlin, since a native binary has no resources to read
 * it from: the version, the commit a snapshot was built from, and the licences it ships under.
 */
@CacheableTask
abstract class BuildInfoTask : DefaultTask() {
  @get:Input
  abstract val version: Property<String>

  @get:Input
  abstract val commit: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val license: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val notices: RegularFileProperty

  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  @TaskAction
  fun write() {
    val licenses = "Kiff\n\n" + license.get().asFile.readText().trimEnd() + "\n\n\n" +
      notices.get().asFile.readText()
    val source = """
      |package com.linroid.kiff.cli
      |
      |/** Written by the build; see cli/build.gradle.kts. */
      |internal object BuildInfo {
      |  const val VERSION: String = ${literal(version.get())}
      |  const val COMMIT: String = ${literal(commit.get())}
      |  const val LICENSES: String = ${literal(licenses)}
      |}
      |
    """.trimMargin()
    val file = outputDirectory.file("com/linroid/kiff/cli/BuildInfo.kt").get().asFile
    file.parentFile.mkdirs()
    file.writeText(source)
  }

  /** [text] as a Kotlin string literal, a line at a time so the generated file stays readable. */
  private fun literal(text: String): String {
    if (text.isEmpty()) return "\"\""
    return text.split("\n").let { lines ->
      lines.mapIndexed { i, line ->
        val end = if (i < lines.lastIndex) "\\n" else ""
        "\"" + line.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + end + "\""
      }.filter { it != "\"\"" }.joinToString(" +\n    ")
    }
  }
}

// The JVM build is also the main distribution, so :cli:installDist and :cli:distZip make what they
// always have: kiff-<version>.zip holding kiff-<version>/bin/kiff, which `kiff --use` unpacks.
pluginManager.withPlugin("distribution") {
  val distributions = the<DistributionContainer>()
  distributions.named("main") {
    distributionBaseName.set("kiff")
    contents { with(distributions.getByName("jvm").contents) }
  }
}
