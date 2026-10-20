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
 *
 * An alignment only lasts until the next insertion or deletion shifts it, and in rebuilt code that
 * happens every few methods. An exact match restores it, but where nearly every instruction
 * differs - an R8 build renumbers the ids they reference - one long enough to be found can be
 * hundreds of bytes away, and everything up to it would be carried as literals. So when the
 * alignment stops agreeing altogether, the scanner first looks for it a short way either side.
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
        val alignedOffset = if (aligned) position + alignment else NO_OFFSET
        if (index.lookup(hash, target, position, to, match, alignedOffset)) {
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
        var length = diffLength(target, position, to, position + alignment)
        if (length < DeltaOp.MIN_DIFF) {
          val shifted = realign(target, position, to, alignment)
          if (shifted != alignment) {
            alignment = shifted
            length = diffLength(target, position, to, position + alignment)
          }
        }
        if (length >= DeltaOp.MIN_DIFF) {
          val sourcePosition = position + alignment
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
   * Where a DIFF that stopped at [position] should resume: the nearest alignment within
   * [REALIGN_RADIUS] of [alignment] that agrees well enough to start one, or [alignment] itself.
   *
   * Only an alignment that has stopped agreeing altogether is replaced. One that merely fails a
   * chunk usually holds again a little further on, and a table is where that matters: a column of
   * offsets that all moved by the same amount differs from its source in every entry, by the same
   * difference every time, which packs to almost nothing. A few entries along, the same column
   * agrees in more bytes, by differences that pack far worse - so agreeing in more bytes is no
   * reason to move.
   *
   * Only even shifts are tried. Dex code moves in two-byte units, and on the release builds this
   * was measured on, trying odd shifts as well took twice the searching for a tenth of a percent,
   * and made one patch larger.
   */
  private fun realign(target: ByteArray, position: Int, to: Int, alignment: Int): Int {
    if (!collapsed(target, position, to, position + alignment)) return alignment
    var distance = REALIGN_STEP
    while (distance <= REALIGN_RADIUS) {
      val before = alignment - distance
      if (chunkAgrees(target, position, to, position + before, DeltaOp.DIFF_EQUAL_TO_START)) {
        return before
      }
      val after = alignment + distance
      if (chunkAgrees(target, position, to, position + after, DeltaOp.DIFF_EQUAL_TO_START)) {
        return after
      }
      distance += REALIGN_STEP
    }
    return alignment
  }

  /** Whether at most [COLLAPSED] of the next [COLLAPSE_WINDOW] bytes agree at [sourcePosition]. */
  private fun collapsed(target: ByteArray, position: Int, to: Int, sourcePosition: Int): Boolean {
    if (sourcePosition < 0) return true
    val count = minOf(COLLAPSE_WINDOW, to - position, source.size - sourcePosition)
    var equal = 0
    for (i in 0 until count) {
      if (source[sourcePosition + i] == target[position + i] && ++equal > COLLAPSED) return false
    }
    return true
  }

  /**
   * How far the aligned source keeps agreeing with the target, in whole chunks: the first chunk has
   * to agree strongly for a DIFF to be worth starting, later ones only by a majority.
   */
  private fun diffLength(target: ByteArray, position: Int, to: Int, sourcePosition: Int): Int {
    var length = 0
    while (true) {
      val required = if (length == 0) {
        DeltaOp.DIFF_EQUAL_TO_START
      } else {
        DeltaOp.DIFF_EQUAL_TO_CONTINUE
      }
      if (!chunkAgrees(target, position + length, to, sourcePosition + length, required)) break
      length += DeltaOp.DIFF_CHUNK
    }
    return length
  }

  /** Whether at least [required] bytes of the chunk at [position] agree with the source. */
  private fun chunkAgrees(
    target: ByteArray,
    position: Int,
    to: Int,
    sourcePosition: Int,
    required: Int
  ): Boolean {
    if (sourcePosition < 0) return false
    if (to - position < DeltaOp.DIFF_CHUNK) return false
    if (source.size - sourcePosition < DeltaOp.DIFF_CHUNK) return false
    // Gives up as soon as too many bytes differ for the rest to make up for them, which is most of
    // the time when [realign] is trying alignments that do not hold.
    val allowed = DeltaOp.DIFF_CHUNK - required
    var unequal = 0
    for (i in 0 until DeltaOp.DIFF_CHUNK) {
      if (source[sourcePosition + i] != target[position + i] && ++unequal > allowed) return false
    }
    return true
  }

  private companion object {
    /** Sentinel that can never be `position - 1`, so the first hash is always computed in full. */
    const val NO_HASH = Int.MIN_VALUE

    /** Sentinel for "no alignment to prefer" when looking up a match. */
    const val NO_OFFSET = -1

    /**
     * How far either side of a collapsed alignment [realign] looks, and in what steps. Measured on
     * the ten most recent releases of a 71 MB app: 32 bytes recovers about four fifths of what 128
     * does, and 256 adds only a little more for noticeably more encode time.
     */
    const val REALIGN_RADIUS = 128
    const val REALIGN_STEP = 2

    /** An alignment has collapsed when no more than a quarter of the bytes ahead agree. */
    const val COLLAPSE_WINDOW = 128
    const val COLLAPSED = 32
  }
}
