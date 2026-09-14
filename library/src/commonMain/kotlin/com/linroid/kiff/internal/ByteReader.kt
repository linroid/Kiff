package com.linroid.kiff.internal

import com.linroid.kiff.KiffException

/** Bounds-checked reader over patch bytes; failures surface as [KiffException.InvalidPatch]. */
internal class ByteReader(private val data: ByteArray, private var position: Int = 0) {

  val offset: Int get() = position
  val remaining: Int get() = data.size - position

  fun readByte(): Int {
    ensure(1)
    return data[position++].toInt() and 0xFF
  }

  fun readBytes(count: Int): ByteArray {
    ensure(count)
    val result = data.copyOfRange(position, position + count)
    position += count
    return result
  }

  fun skip(count: Int) {
    ensure(count)
    position += count
  }

  fun readUInt32(): UInt {
    ensure(4)
    var value = 0u
    repeat(4) {
      value = (value shl 8) or (data[position++].toUInt() and 0xFFu)
    }
    return value
  }

  fun readVarLong(): Long {
    var result = 0L
    var shift = 0
    while (true) {
      if (shift > 63) throw KiffException.InvalidPatch("Malformed varint at offset $position")
      val byte = readByte()
      result = result or ((byte.toLong() and 0x7F) shl shift)
      if (byte and 0x80 == 0) return result
      shift += 7
    }
  }

  fun readVarInt(): Int {
    val value = readVarLong()
    if (value > Int.MAX_VALUE) {
      throw KiffException.InvalidPatch("Varint $value exceeds Int range")
    }
    return value.toInt()
  }

  fun readSignedVarInt(): Int {
    val zigzag = readVarLong().toInt()
    return (zigzag ushr 1) xor -(zigzag and 1)
  }

  private fun ensure(count: Int) {
    if (count < 0 || position + count > data.size) {
      throw KiffException.InvalidPatch(
        "Patch truncated: need $count byte(s) at offset $position of ${data.size}"
      )
    }
  }
}
