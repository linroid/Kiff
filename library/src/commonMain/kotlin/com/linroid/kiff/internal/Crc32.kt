package com.linroid.kiff.internal

/** Standard CRC-32 (IEEE 802.3, the polynomial used by zip and gzip). */
internal object Crc32 {

  private val table = IntArray(256) { index ->
    var c = index
    repeat(8) {
      c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
    }
    c
  }

  fun compute(data: ByteArray, from: Int = 0, to: Int = data.size): UInt {
    var crc = -1
    for (i in from until to) {
      crc = table[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv().toUInt()
  }
}
