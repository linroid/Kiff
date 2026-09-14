package com.linroid.kiff.io

import com.linroid.kiff.KiffException

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
