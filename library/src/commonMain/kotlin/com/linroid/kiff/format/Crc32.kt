package com.linroid.kiff.format

import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readFully

/** Standard CRC-32 (IEEE 802.3, the polynomial used by zip and gzip). */
internal object Crc32 {

  private val table = IntArray(256) { index ->
    var c = index
    repeat(8) {
      c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
    }
    c
  }

  fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): UInt =
    update(-1, data, from, to).inv().toUInt()

  /**
   * Checksums a whole source a chunk at a time, so a file need not be held in memory for it.
   *
   * Every byte the source claims is read or the call fails: a source that ended early used to stop
   * the loop quietly, and a checksum of a prefix passes for one of the whole.
   */
  fun compute(source: SeekableSource): UInt {
    val buffer = ByteArray(CHUNK)
    var crc = -1
    var position = 0L
    val size = source.size
    while (position < size) {
      val count = minOf(CHUNK.toLong(), size - position).toInt()
      source.readFully(position, buffer, 0, count)
      crc = update(crc, buffer, 0, count)
      position += count
    }
    return crc.inv().toUInt()
  }

  /**
   * A checksum computed as the bytes go past, for a restore that never holds them all at once.
   *
   * Two run at a time while a patch is applied: one over the whole target, and one that is started
   * and finished around each region so a failure can name where it happened.
   */
  class Running {
    private var crc = -1

    fun update(data: ByteArray, from: Int, to: Int) {
      crc = Crc32.update(crc, data, from, to)
    }

    fun value(): UInt = crc.inv().toUInt()
  }

  internal fun update(seed: Int, data: ByteArray, from: Int, to: Int): Int {
    var crc = seed
    for (i in from until to) {
      crc = table[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc
  }

  private const val CHUNK = 1 shl 16
}

/** Eight lowercase hex digits, the way a CRC-32 is conventionally shown. */
internal fun UInt.toHex(): String = toString(16).padStart(8, '0')
