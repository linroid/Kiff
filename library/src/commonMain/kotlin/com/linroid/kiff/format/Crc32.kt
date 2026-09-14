package com.linroid.kiff.format

import com.linroid.kiff.io.SeekableSource

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

  /** Checksums a whole source a chunk at a time, so a file need not be held in memory for it. */
  fun compute(source: SeekableSource): UInt {
    val buffer = ByteArray(CHUNK)
    var crc = -1
    var position = 0L
    while (position < source.size) {
      val count = source.read(position, buffer, 0, CHUNK)
      if (count <= 0) break
      crc = update(crc, buffer, 0, count)
      position += count
    }
    return crc.inv().toUInt()
  }

  private fun update(seed: Int, data: ByteArray, from: Int, to: Int): Int {
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
