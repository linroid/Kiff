package com.linroid.kiff.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import okio.BufferedSink
import okio.IOException
import okio.Path
import platform.posix.chmod
import platform.posix.errno
import platform.posix.execvp
import platform.posix.fflush
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.stat
import platform.posix.strerror
import platform.posix.system

internal actual fun standardOutput(): BufferedSink = standardOutputSink(prepare = null)

@OptIn(ExperimentalForeignApi::class)
internal actual fun environment(name: String): String? = getenv(name)?.toKString()

internal actual fun homeDirectory(): String =
  environment("HOME")?.takeIf { it.isNotEmpty() } ?: throw IOException("HOME is not set")

/**
 * Runs [command] through `/bin/sh`, each argument quoted so the shell reads it as it is:
 * Kotlin/Native has no posix_spawn, and a fork from Kotlin is not safe while the runtime has other
 * threads.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun runCommand(command: List<String>, stdout: Path?, stderr: Path?): Int {
  val line = buildString {
    append(command.joinToString(" ") { shellQuoted(it) })
    if (stdout != null) append(" >").append(shellQuoted(stdout.toString()))
    if (stderr != null) append(" 2>").append(shellQuoted(stderr.toString()))
  }
  fflush(null)
  val status = system(line)
  if (status == -1) throw IOException("Could not run ${command.first()}: ${reason()}")
  // The wait status: the exit status in its second byte, or the signal in the low seven bits.
  val signal = status and 0x7F
  if (signal != 0) return 128 + signal
  val exit = (status shr 8) and 0xFF
  // The shell's own answer when there is no such program to run.
  if (exit == 127) throw ProgramNotFoundException(command.first())
  return exit
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun handOver(command: List<String>, environment: Map<String, String>): Int {
  for ((name, value) in environment) setenv(name, value, 1)
  // Whatever this process has buffered goes out before another program takes its place.
  fflush(null)
  memScoped {
    val argv = allocArrayOf(command.map { it.cstr.ptr } + null)
    execvp(command.first(), argv)
  }
  // execvp only returns when it failed.
  throw IOException(reason())
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun makeExecutable(path: Path) {
  memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) throw IOException("Cannot read $path: ${reason()}")
    // Executable by everyone, as the JVM build makes it; mode_t's width differs between platforms.
    val mode = info.st_mode.toInt() and 0xFFF
    if (chmod(path.toString(), (mode or 0b001_001_001).convert()) != 0) {
      throw IOException("Cannot make $path executable: ${reason()}")
    }
  }
}

@OptIn(ExperimentalForeignApi::class)
private fun reason(): String = strerror(errno)?.toKString() ?: "error $errno"
