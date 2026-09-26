package com.linroid.kiff.format

import com.linroid.kiff.KiffException

/** Bounds-checked reader over patch bytes; failures surface as [KiffException.InvalidPatch]. */
internal class ByteReader(private val data: ByteArray, private var position: Int = 0) {

  val offset: Int get() = position
  val remaining: Int get() = data.size - position

  /** The buffer being read, so a nested block can be decoded in place rather than copied out. */
  val bytes: ByteArray get() = data

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
      // The tenth group has room for one bit; anything above it would be silently dropped.
      if (shift == 63 && byte and 0x7E != 0) {
        throw KiffException.InvalidPatch("Varint wider than 64 bits at offset $position")
      }
      result = result or ((byte.toLong() and 0x7F) shl shift)
      if (byte and 0x80 == 0) return result
      shift += 7
    }
  }

  /**
   * A varint that has to be a size, a count or a length - so not negative, which a varint that
   * sets the top bit of its 64 would otherwise be.
   */
  fun readVarInt(): Int {
    val value = readVarLong()
    if (value < 0 || value > Int.MAX_VALUE) {
      throw KiffException.InvalidPatch("Varint $value is not a length this can hold")
    }
    return value.toInt()
  }

  fun readSignedVarLong(): Long {
    val zigzag = readVarLong()
    return (zigzag ushr 1) xor -(zigzag and 1)
  }

  private fun ensure(count: Int) {
    // Subtracted rather than added: a count near Int.MAX_VALUE would wrap the sum past the check.
    if (count < 0 || count > data.size - position) {
      throw KiffException.InvalidPatch(
        "Patch truncated: need $count byte(s) at offset $position of ${data.size}"
      )
    }
  }
}
