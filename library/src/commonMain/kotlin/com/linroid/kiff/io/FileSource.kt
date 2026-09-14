package com.linroid.kiff.io

import com.linroid.kiff.KiffException
import okio.FileHandle
import okio.IOException
import okio.Path.Companion.toPath

/**
 * Opens [path] for random-access reading, backed by okio's `FileHandle`.
 *
 * Available wherever the target has a file system; the browser JS target does not, and throws
 * [KiffException.UnsupportedInput].
 */
fun fileSource(path: String): SeekableSource = try {
  FileHandleSource(systemFileSystem.openReadOnly(path.toPath()))
} catch (e: IOException) {
  throw KiffException.UnsupportedInput("Cannot open $path for reading: ${e.message}")
}

private class FileHandleSource(private val handle: FileHandle) : SeekableSource {

  override val size: Long = handle.size()

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    return handle.read(position, into, offset, length)
  }

  override fun close() = handle.close()
}
