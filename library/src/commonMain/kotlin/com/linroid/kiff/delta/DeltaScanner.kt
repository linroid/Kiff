package com.linroid.kiff.delta

/**
 * Greedy matcher that turns a target region into copy / diff / add / run instructions.
 *
 * A scanner describes any number of target regions against one [MatchIndex], so the archive
 * algorithms can index a single entry and still emit copies reaching anywhere in the source.
 *
 * Beyond exact matches it tracks an *alignment*: the source offset the target is currently running
 * parallel to. When no match is found at a position but the aligned source bytes still mostly
 * agree, the region is emitted as a byte-wise difference instead of as literals. That is what keeps
 * patches small for recompiled code, where long stretches are identical except for embedded
 * offsets.
 */
internal class DeltaScanner(private val index: MatchIndex) {

  private val source = index.source
  private val match = Match()

  /**
   * @param initialAlignment source offset that `target[from]` is expected to correspond to, minus
   *   [from]; archive algorithms pass the offset of the matching entry so a changed entry can start
   *   out aligned.
   */
  fun scan(
    target: ByteArray,
    from: Int,
    to: Int,
    writer: DeltaWriter,
    initialAlignment: Int = 0
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
        writer.add(target, literalStart, position - back)
        writer.copy(matchOffset - back, matchLength + back)
        alignment = matchOffset - position
        aligned = true
        position += matchLength
        literalStart = position
        hashPosition = NO_HASH
        nextDiffAttempt = position
        continue
      }

      if (runLength > 0) {
        writer.add(target, literalStart, position)
        writer.run(target[position], runLength)
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
          writer.add(target, literalStart, position)
          writer.diff(source, sourcePosition, target, position, length)
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
    writer.add(target, literalStart, to)
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
