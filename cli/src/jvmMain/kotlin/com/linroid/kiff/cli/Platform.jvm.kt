package com.linroid.kiff.cli

import java.io.File
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.sink

internal actual val systemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual val temporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

internal actual fun standardOutput(): BufferedSink = System.out.sink().buffer()

internal actual fun printError(line: String) {
  System.err.println(line)
}

internal actual fun exit(status: Int): Nothing = kotlin.system.exitProcess(status)

internal actual fun environment(name: String): String? = System.getenv(name)

internal actual fun homeDirectory(): String = System.getProperty("user.home")

/** The platforms a release attaches a native kiff for. */
private val RELEASED_PLATFORMS =
  setOf("linux-x64", "linux-arm64", "macos-arm64", "macos-x64", "windows-x64")

internal actual val nativePlatform: String? = run {
  val os = System.getProperty("os.name").orEmpty()
  val system = when {
    os.startsWith("Linux") -> "linux"
    os.startsWith("Mac") -> "macos"
    os.startsWith("Windows") -> "windows"
    else -> return@run null
  }
  val cpu = when (System.getProperty("os.arch")) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> return@run null
  }
  "$system-$cpu".takeIf { it in RELEASED_PLATFORMS }
}

internal actual val runningJavaHome: String? = System.getProperty("java.home")

internal actual val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

internal actual fun runCommand(command: List<String>, stdout: Path?, stderr: Path?): Int {
  val builder = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.INHERIT)
  builder.redirectOutput(stdout?.let { ProcessBuilder.Redirect.to(it.toFile()) } ?: inherit)
  builder.redirectError(stderr?.let { ProcessBuilder.Redirect.to(it.toFile()) } ?: inherit)
  return start(builder).waitFor()
}

internal actual fun handOver(command: List<String>, environment: Map<String, String>): Int {
  val builder = ProcessBuilder(command).inheritIO()
  builder.environment().putAll(environment)
  return start(builder).waitFor()
}

private val inherit = ProcessBuilder.Redirect.INHERIT

private fun start(builder: ProcessBuilder): Process {
  val program = builder.command().first()
  // Java says only "error=2" for a program that is not there, and nothing more specific than an
  // IOException, so whether it is there is asked first.
  if (File(program).parent == null && !onPath(program)) throw ProgramNotFoundException(program)
  return builder.start()
}

private fun onPath(program: String): Boolean {
  val names = if (isWindows) listOf("$program.exe", program) else listOf(program)
  return System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
    .any { dir -> dir.isNotEmpty() && names.any { File(dir, it).canExecute() } }
}

internal actual fun makeExecutable(path: Path) {
  path.toFile().setExecutable(true, false)
}
