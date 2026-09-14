package com.linroid.kiff.internal

/** Append-only byte buffer with the primitives the patch format needs. */
internal class ByteWriter(initialCapacity: Int = 64) {

  private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))
  private var length = 0

  val size: Int get() = length

  fun writeByte(value: Int) {
    ensure(1)
    buffer[length++] = value.toByte()
  }

  fun writeBytes(data: ByteArray, from: Int = 0, to: Int = data.size) {
    val count = to - from
    if (count <= 0) return
    ensure(count)
    data.copyInto(buffer, length, from, to)
    length += count
  }

  /** Big-endian, so patch headers are readable in a hex dump. */
  fun writeUInt32(value: UInt) {
    ensure(4)
    buffer[length++] = (value shr 24).toByte()
    buffer[length++] = (value shr 16).toByte()
    buffer[length++] = (value shr 8).toByte()
    buffer[length++] = value.toByte()
  }

  fun writeVarLong(value: Long) {
    require(value >= 0) { "varint must be non-negative: $value" }
    var remaining = value
    while (remaining >= 0x80) {
      writeByte(((remaining and 0x7F) or 0x80).toInt())
      remaining = remaining ushr 7
    }
    writeByte(remaining.toInt())
  }

  fun writeVarInt(value: Int) = writeVarLong(value.toLong())

  /** Zig-zag encoding keeps small negative deltas short. */
  fun writeSignedVarInt(value: Int) {
    val zigzag = (value shl 1) xor (value shr 31)
    writeVarLong(zigzag.toLong() and 0xFFFFFFFFL)
  }

  fun toByteArray(): ByteArray = buffer.copyOf(length)

  private fun ensure(extra: Int) {
    val required = length + extra
    if (required <= buffer.size) return
    var capacity = buffer.size
    while (capacity < required) {
      capacity = if (capacity > Int.MAX_VALUE / 2) Int.MAX_VALUE else capacity * 2
    }
    buffer = buffer.copyOf(capacity)
  }
}
