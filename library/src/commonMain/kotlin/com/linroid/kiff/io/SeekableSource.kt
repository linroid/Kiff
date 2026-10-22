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
public interface SeekableSource : AutoCloseable {

  /** Total number of readable bytes. */
  public val size: Long

  /**
   * Reads at most [length] bytes starting at [position] into [into] at [offset].
   *
   * Kiff asks only for a non-negative [position] and a range within [into]. While [position] is
   * below [size], a read of a positive [length] reads at least one byte, waiting for it if it has
   * to; one that cannot is taken as the source ending early. [size] must not change while Kiff is
   * reading.
   *
   * @return the number of bytes read, which may be fewer than requested; 0 only when [length] is 0,
   *   and -1 once [position] reaches [size].
   */
  public fun read(
    position: Long,
    into: ByteArray,
    offset: Int = 0,
    length: Int = into.size - offset
  ): Int

  override fun close() {}
}

/**
 * Reads exactly [length] bytes at [position], failing if the source ends first - which a read of
 * nothing, 0 or -1, before then is taken to mean.
 */
public fun SeekableSource.readFully(
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

/**
 * Reads exactly [length] bytes at [position] into a new array, failing as [readFully] does if the
 * source ends first.
 *
 * This is how a [com.linroid.kiff.container.ContainerFormat] reads a header or a directory: just
 * those bytes, wherever they sit, so a format never has to hold the file it describes.
 */
public fun SeekableSource.readBytes(position: Long, length: Int): ByteArray {
  val bytes = ByteArray(length)
  readFully(position, bytes)
  return bytes
}
