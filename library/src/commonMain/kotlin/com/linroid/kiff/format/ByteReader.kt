package com.linroid.kiff.format

import com.linroid.kiff.KiffException

/**
 * Bounds-checked reader over patch bytes; failures surface as [KiffException.InvalidPatch].
 *
 * [limit] is where the bytes this reader may see end. A block of the patch that declares its own
 * length is read through a [window] of exactly that length, so it can neither read into whatever
 * follows it nor leave part of itself unread without [requireConsumed] noticing.
 */
internal class ByteReader(
  private val data: ByteArray,
  private var position: Int = 0,
  private val limit: Int = data.size
) {

  val offset: Int get() = position
  val remaining: Int get() = limit - position

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

  /** The next [count] bytes as a reader of their own, decoded in place rather than copied out. */
  fun window(count: Int): ByteReader {
    ensure(count)
    val inner = ByteReader(data, position, position + count)
    position += count
    return inner
  }

  /** A second reader at the same place over the same bytes, for reading ahead. */
  fun copy(): ByteReader = ByteReader(data, position, limit)

  /** Refuses a block that ended before its declared length did. */
  fun requireConsumed(what: String) {
    if (remaining != 0) {
      throw KiffException.InvalidPatch("$what end $remaining byte(s) before their declared length")
    }
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
    if (count < 0 || count > limit - position) {
      throw KiffException.InvalidPatch(
        "Patch truncated: need $count byte(s) at offset $position, which ends at $limit"
      )
    }
  }
}
