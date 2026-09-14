package com.linroid.kiff.io

import com.linroid.kiff.KiffException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread

@OptIn(ExperimentalForeignApi::class)
actual fun fileSource(path: String): SeekableSource {
  val size = SystemFileSystem.metadataOrNull(Path(path))?.size
    ?: throw KiffException.UnsupportedInput("Cannot stat $path")
  val file = fopen(path, "rb")
    ?: throw KiffException.UnsupportedInput("Cannot open $path for reading")
  return PosixFileSource(file, size)
}

/**
 * Seeking is left to [seekTo], because `fseek` takes a C `long` - 64-bit on Unix, 32-bit on Windows
 * - and a signature whose width varies cannot be shared across native targets.
 */
@OptIn(ExperimentalForeignApi::class)
private class PosixFileSource(
  private val file: CPointer<FILE>,
  override val size: Long
) : SeekableSource {

  private var cursor = -1L

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    val count = minOf(length.toLong(), size - position).toInt()
    if (count <= 0) return 0
    if (position != cursor) {
      seekTo(file, position)
      cursor = position
    }
    val read = into.usePinned { pinned ->
      fread(pinned.addressOf(offset), 1.convert(), count.convert(), file).toLong()
    }
    if (read <= 0L) return -1
    cursor += read
    return read.toInt()
  }

  override fun close() {
    fclose(file)
  }
}

/** Moves the read cursor of [file] to an absolute [position]. */
@OptIn(ExperimentalForeignApi::class)
internal expect fun seekTo(file: CPointer<FILE>, position: Long)
