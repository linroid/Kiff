package com.linroid.kiff.io

import com.linroid.kiff.KiffException
import okio.Buffer
import okio.FileHandle
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.use

/**
 * Opens [path] for random-access reading, backed by okio's `FileHandle`.
 *
 * A pipe, a FIFO or a device has no size to trust and cannot be read at random, so one is read to
 * its end once and held in memory instead. Available wherever the target has a file system; the
 * browser JS target does not, and throws [KiffException.UnsupportedInput].
 */
fun fileSource(path: String): SeekableSource = try {
  val file = path.toPath()
  // Followed through any symlink, which describes itself rather than what it points at.
  val metadata = systemFileSystem.metadataOrNull(systemFileSystem.canonicalize(file))
  if (metadata != null && !metadata.isRegularFile && !metadata.isDirectory) {
    // A pipe's size is what happens to be buffered in it - nothing at all on Linux - so trusting it
    // would make a patch for an empty file and report success.
    ByteArraySource(readToEnd(file, path))
  } else {
    val handle = systemFileSystem.openReadOnly(file)
    try {
      FileHandleSource(handle)
    } catch (e: Throwable) {
      handle.close()
      throw e
    }
  }
} catch (e: IOException) {
  throw KiffException.UnsupportedInput("Cannot open $path for reading: ${e.message}")
}

private fun readToEnd(file: Path, path: String): ByteArray {
  val buffer = Buffer()
  systemFileSystem.source(file).use { stream ->
    while (stream.read(buffer, READ_CHUNK) != -1L) {
      if (buffer.size > Int.MAX_VALUE - 8) {
        throw KiffException.UnsupportedInput("$path holds more than can be read into memory")
      }
    }
  }
  return buffer.readByteArray()
}

private const val READ_CHUNK = 64L * 1024

private class FileHandleSource(private val handle: FileHandle) : SeekableSource {

  override val size: Long = handle.size()

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    return handle.read(position, into, offset, length)
  }

  override fun close() = handle.close()
}
