package com.linroid.kiff.io

import com.linroid.kiff.KiffException

/**
 * Reads a whole source into memory, returning the backing array unchanged when there already is
 * one.
 *
 * The patch format and the [com.linroid.kiff.delta.DeltaAlgorithm] contract are addressed in
 * [Long], but the bundled search still works over a [ByteArray], so today a source it indexes has
 * to fit in one. Callers materialize once, up front, and every scanner built afterwards reuses that
 * array rather than re-reading the file.
 */
internal fun SeekableSource.materialize(label: String): ByteArraySource {
  if (this is ByteArraySource) return this
  if (size > Int.MAX_VALUE) {
    throw KiffException.UnsupportedInput(
      "$label is ${size} bytes; the bundled algorithm indexes at most ${Int.MAX_VALUE}. " +
        "The patch format addresses more, but a streaming search is not implemented yet."
    )
  }
  val bytes = ByteArray(size.toInt())
  readFully(0, bytes)
  return ByteArraySource(bytes)
}

/** Fails loudly rather than silently truncating when a 64-bit value has to index an array. */
internal fun Long.toIntIndex(what: String): Int {
  if (this < 0 || this > Int.MAX_VALUE) {
    throw KiffException.UnsupportedInput("$what $this does not fit in memory")
  }
  return toInt()
}

/** Like [toIntIndex] but for a relative offset, which may legitimately be negative. */
internal fun Long.toIntOffset(what: String): Int {
  if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) {
    throw KiffException.UnsupportedInput("$what $this does not fit in memory")
  }
  return toInt()
}
