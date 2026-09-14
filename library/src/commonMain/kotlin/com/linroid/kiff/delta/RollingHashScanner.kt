package com.linroid.kiff.delta

import com.linroid.kiff.io.materialize
import com.linroid.kiff.io.toIntIndex
import com.linroid.kiff.io.toIntOffset
import com.linroid.kiff.io.SeekableSource

/**
 * Greedy matcher: the default [DeltaAlgorithm]. It turns a target region into copy / diff / add /
 * run instructions, hashing every target position in constant time and verifying candidate matches
 * against a [MatchIndex] built over one source range.
 *
 * Beyond exact matches it tracks an *alignment*: the source offset the target is currently running
 * parallel to. When no match is found at a position but the aligned source bytes still mostly
 * agree, the region is emitted as a byte-wise difference instead of as literals. That is what keeps
 * patches small for recompiled code, where long stretches are identical except for embedded
 * offsets.
 */
internal class RollingHashScanner(private val index: MatchIndex) : DeltaScanner {

  private val source = index.source
  private val match = Match()

  override fun scan(
    target: SeekableSource,
    from: Long,
    to: Long,
    sink: DeltaSink,
    initialAlignment: Long
  ) {
    scanBytes(
      target.materialize("Target").bytes,
      from.toIntIndex("Target offset"),
      to.toIntIndex("Target offset"),
      sink,
      initialAlignment.toIntOffset("Alignment")
    )
  }

  private fun scanBytes(
    target: ByteArray,
    from: Int,
    to: Int,
    sink: DeltaSink,
    initialAlignment: Int
  ) {
    var position = from
    var literalStart = from
    var hash = 0
    var hashPosition = NO_HASH
    var alignment = initialAlignment
    var aligned = true
    var nextDiffAttempt = from

    while (position < to) {
      val runLength = runLengthAt(target, position, to)

      var matchOffset = -1
      var matchLength = 0
      if (position + RollingHash.WINDOW <= to) {
        hash = if (hashPosition == position - 1) {
          RollingHash.roll(hash, target[position - 1], target[position + RollingHash.WINDOW - 1])
        } else {
          RollingHash.of(target, position)
        }
        hashPosition = position
        if (index.lookup(hash, target, position, to, match)) {
          matchOffset = match.sourceOffset
          matchLength = match.length
        }
      }

      if (matchLength >= DeltaOp.MIN_MATCH && matchLength >= runLength) {
        val back = index.backwardLength(matchOffset, target, position, literalStart)
        sink.add(target, literalStart, position - back)
        sink.copy((matchOffset - back).toLong(), (matchLength + back).toLong())
        alignment = matchOffset - position
        aligned = true
        position += matchLength
        literalStart = position
        hashPosition = NO_HASH
        nextDiffAttempt = position
        continue
      }

      if (runLength > 0) {
        sink.add(target, literalStart, position)
        sink.run(target[position], runLength.toLong())
        aligned = false
        position += runLength
        literalStart = position
        hashPosition = NO_HASH
        nextDiffAttempt = position
        continue
      }

      if (aligned && position >= nextDiffAttempt) {
        val sourcePosition = position + alignment
        val length = diffLength(target, position, to, sourcePosition)
        if (length >= DeltaOp.MIN_DIFF) {
          sink.add(target, literalStart, position)
          sink.diff(sourcePosition.toLong(), target, position, position + length)
          position += length
          literalStart = position
          hashPosition = NO_HASH
          nextDiffAttempt = position
          continue
        }
        // The alignment does not hold here; do not re-test every single byte.
        nextDiffAttempt = position + DeltaOp.DIFF_CHUNK
      }

      position++
    }
    sink.add(target, literalStart, to)
  }

  /** Length of the repeated-byte run starting at [position], or 0 if it is not worth a RUN. */
  private fun runLengthAt(target: ByteArray, position: Int, to: Int): Int {
    if (position + DeltaOp.MIN_RUN > to) return 0
    val value = target[position]
    if (target[position + 1] != value) return 0
    var end = position + 2
    while (end < to && target[end] == value) end++
    val length = end - position
    return if (length >= DeltaOp.MIN_RUN) length else 0
  }

  /**
   * How far the aligned source keeps agreeing with the target, in whole chunks: the first chunk has
   * to agree strongly for a DIFF to be worth starting, later ones only by a majority.
   */
  private fun diffLength(target: ByteArray, position: Int, to: Int, sourcePosition: Int): Int {
    if (sourcePosition < 0) return 0
    var length = 0
    while (true) {
      val targetRoom = to - (position + length)
      val sourceRoom = source.size - (sourcePosition + length)
      if (targetRoom < DeltaOp.DIFF_CHUNK || sourceRoom < DeltaOp.DIFF_CHUNK) break
      var equal = 0
      for (i in 0 until DeltaOp.DIFF_CHUNK) {
        if (source[sourcePosition + length + i] == target[position + length + i]) equal++
      }
      val required = if (length == 0) {
        DeltaOp.DIFF_EQUAL_TO_START
      } else {
        DeltaOp.DIFF_EQUAL_TO_CONTINUE
      }
      if (equal < required) break
      length += DeltaOp.DIFF_CHUNK
    }
    return length
  }

  private companion object {
    /** Sentinel that can never be `position - 1`, so the first hash is always computed in full. */
    const val NO_HASH = Int.MIN_VALUE
  }
}
