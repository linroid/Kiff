package com.linroid.kiff.delta

/**
 * Polynomial rolling hash over a fixed [WINDOW] of bytes, so the scanner can hash every target
 * position in constant time.
 */
internal object RollingHash {

  const val WINDOW = 16

  private const val BASE = 0x01000193

  private val removeFactor: Int = run {
    var factor = 1
    repeat(WINDOW - 1) { factor *= BASE }
    factor
  }

  fun of(data: ByteArray, from: Int): Int {
    var hash = 0
    for (i in from until from + WINDOW) {
      hash = hash * BASE + (data[i].toInt() and 0xFF)
    }
    return hash
  }

  fun roll(hash: Int, outgoing: Byte, incoming: Byte): Int =
    (hash - (outgoing.toInt() and 0xFF) * removeFactor) * BASE + (incoming.toInt() and 0xFF)

  /** Spreads the polynomial hash over the table index range. */
  fun mix(hash: Int, bits: Int): Int = (hash * -1640531527) ushr (32 - bits)
}
