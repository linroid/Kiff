package com.linroid.kiff.delta

/**
 * Hash index over strided [RollingHash.WINDOW]-byte blocks of a source range.
 *
 * Sampling every `stride` bytes instead of every byte keeps the index affordable for APK-sized
 * inputs, at the price of missing matches shorter than `WINDOW + stride - 1` bytes, which the
 * literal compressor picks up instead.
 *
 * Indexing is limited to `[from, to)` but matches may grow past those bounds, so an archive
 * patcher can index one entry - a much smaller range, and therefore a finer stride and fewer hash
 * collisions than the whole file - while still emitting copies that reach anywhere in the source.
 */
internal class MatchIndex(
  val source: ByteArray,
  private val from: Int = 0,
  private val to: Int = source.size
) {

  private val span = to - from

  /**
   * How far apart the indexed blocks sit. Always a power of two, so the builder can mask rather
   * than divide.
   *
   * Four is the floor on purpose, and it has been measured rather than guessed. On a pair of real
   * 75 MB packages, dropping to a stride of two shrinks the patch by about 1% and to one by about
   * 3%, for a quarter more encoding time and - the reason it is not the default - an index four
   * times the size. A stride of one over an eleven-megabyte range means eleven million blocks and
   * upwards of a hundred megabytes of index, which is not a thing to do on a phone to save 3%.
   *
   * Raising the floor is likewise unattractive: the ranges that matter here are entries and the
   * groups they belong to, and those already sit in the finest band.
   */
  private val stride: Int = when {
    span <= 32 shl 20 -> 4
    span <= 256 shl 20 -> 8
    else -> 16
  }

  private val blockCount: Int =
    if (span < RollingHash.WINDOW) 0 else (span - RollingHash.WINDOW) / stride + 1

  private val tableBits: Int = run {
    var bits = 10
    while (bits < 26 && (1 shl bits) < blockCount) bits++
    bits
  }

  private val head = IntArray(if (blockCount == 0) 0 else 1 shl tableBits) { -1 }
  private val next = IntArray(blockCount)

  init {
    if (blockCount > 0) {
      var hash = RollingHash.of(source, from)
      var position = from
      var block = 0
      val last = to - RollingHash.WINDOW
      while (true) {
        if ((position - from) and (stride - 1) == 0) {
          val slot = RollingHash.mix(hash, tableBits)
          next[block] = head[slot]
          head[slot] = block
          block++
        }
        if (position == last) break
        hash = RollingHash.roll(hash, source[position], source[position + RollingHash.WINDOW])
        position++
      }
    }
  }

  /**
   * Finds the longest verified match for `target[position...]` among blocks sharing [hash].
   * Returns true and fills [best] when a match of at least [RollingHash.WINDOW] bytes exists.
   */
  fun lookup(hash: Int, target: ByteArray, position: Int, targetEnd: Int, best: Match): Boolean {
    best.length = 0
    best.sourceOffset = -1
    if (blockCount == 0) return false
    var block = head[RollingHash.mix(hash, tableBits)]
    var probes = 0
    while (block >= 0 && probes < MAX_PROBES) {
      val offset = from + block * stride
      val length = forwardLength(offset, target, position, targetEnd)
      if (length > best.length) {
        best.length = length
        best.sourceOffset = offset
      }
      block = next[block]
      probes++
    }
    return best.length >= RollingHash.WINDOW
  }

  /** Extends a match backwards, without crossing [targetFloor] or the start of the source. */
  fun backwardLength(sourceOffset: Int, target: ByteArray, position: Int, targetFloor: Int): Int {
    var count = 0
    while (position - count > targetFloor &&
      sourceOffset - count > 0 &&
      source[sourceOffset - count - 1] == target[position - count - 1]
    ) {
      count++
    }
    return count
  }

  private fun forwardLength(
    sourceOffset: Int,
    target: ByteArray,
    position: Int,
    targetEnd: Int
  ): Int {
    val max = minOf(source.size - sourceOffset, targetEnd - position)
    var length = 0
    while (length < max && source[sourceOffset + length] == target[position + length]) {
      length++
    }
    return length
  }

  private companion object {
    const val MAX_PROBES = 16
  }
}

internal class Match {
  var sourceOffset: Int = -1
  var length: Int = 0
}
