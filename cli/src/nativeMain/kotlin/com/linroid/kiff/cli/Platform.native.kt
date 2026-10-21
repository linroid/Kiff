package com.linroid.kiff.cli

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.CpuArchitecture
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import okio.Buffer
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.Sink
import okio.Timeout
import okio.buffer
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.fwrite
import platform.posix.stderr
import platform.posix.stdout

internal actual val systemFileSystem: FileSystem = FileSystem.SYSTEM

internal actual val temporaryDirectory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY

internal actual fun exit(status: Int): Nothing = kotlin.system.exitProcess(status)

@OptIn(ExperimentalNativeApi::class)
internal actual val nativePlatform: String? = run {
  val system = when (Platform.osFamily) {
    OsFamily.LINUX -> "linux"
    OsFamily.MACOSX -> "macos"
    OsFamily.WINDOWS -> "windows"
    else -> return@run null
  }
  val cpu = when (Platform.cpuArchitecture) {
    CpuArchitecture.X64 -> "x64"
    CpuArchitecture.ARM64 -> "arm64"
    else -> return@run null
  }
  "$system-$cpu"
}

internal actual val runningJavaHome: String? = null

@OptIn(ExperimentalNativeApi::class)
internal actual val isWindows: Boolean = Platform.osFamily == OsFamily.WINDOWS

@OptIn(ExperimentalForeignApi::class)
internal actual fun printError(line: String) {
  fputs("$line\n", stderr)
  fflush(stderr)
}

/** Standard output through C's stdio, after [prepare] has run once before the first write. */
@OptIn(ExperimentalForeignApi::class)
internal class StandardOutputSink(private var prepare: (() -> Unit)?) : Sink {
  override fun write(source: Buffer, byteCount: Long) {
    prepare?.invoke()
    prepare = null
    val bytes = source.readByteArray(byteCount)
    if (bytes.isEmpty()) return
    bytes.usePinned { pinned ->
      fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), stdout)
    }
  }

  override fun flush() {
    fflush(stdout)
  }

  override fun timeout(): Timeout = Timeout.NONE

  override fun close() = flush()
}

internal fun standardOutputSink(prepare: (() -> Unit)?): BufferedSink =
  StandardOutputSink(prepare).buffer()
