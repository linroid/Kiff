package com.linroid.kiff.io

import com.linroid.kiff.KiffException
import okio.FileHandle
import okio.IOException
import okio.Path.Companion.toPath

/**
 * Random-access, read-only view over a run of bytes.
 *
 * A delta algorithm cannot consume its inputs sequentially: a `COPY` instruction names an arbitrary
 * source offset, and applying one reads that offset back. So Kiff *addresses* its inputs rather
 * than streaming them, and this interface is the seam a caller implements to keep a large file out
 * of memory - a file handle, a memory mapping, a range-request HTTP client.
 *
 * Positions are [Long], so a source may be larger than 2 GB; one backed by a [ByteArray] cannot be.
 * Implementations are not required to be thread-safe.
 */
interface SeekableSource : AutoCloseable {

  /** Total number of readable bytes. */
  val size: Long

  /**
   * Reads at most [length] bytes starting at [position] into [into] at [offset].
   *
   * @return the number of bytes read, which may be fewer than requested, or -1 at end of input.
   */
  fun read(position: Long, into: ByteArray, offset: Int = 0, length: Int = into.size - offset): Int

  override fun close() {}
}

/** Reads exactly [length] bytes at [position], failing if the source ends first. */
fun SeekableSource.readFully(
  position: Long,
  into: ByteArray,
  offset: Int = 0,
  length: Int = into.size - offset
) {
  var done = 0
  while (done < length) {
    val count = read(position + done, into, offset + done, length - done)
    if (count <= 0) {
      throw KiffException.UnsupportedInput(
        "Source ended after ${position + done} bytes, expected ${position + length}"
      )
    }
    done += count
  }
}

/** A [SeekableSource] over bytes already in memory. */
class ByteArraySource(internal val bytes: ByteArray) : SeekableSource {

  override val size: Long get() = bytes.size.toLong()

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= bytes.size) return -1
    val count = minOf(length.toLong(), bytes.size - position).toInt()
    if (count <= 0) return 0
    bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
    return count
  }
}

/** Wraps these bytes as a [SeekableSource] without copying them. */
fun ByteArray.asSource(): SeekableSource = ByteArraySource(this)

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
