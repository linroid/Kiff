package com.linroid.kiff.delta

/**
 * Greedy copy/add/run matcher.
 *
 * One scanner indexes the whole source once and can then describe any number of target regions, so
 * the archive algorithms can delta an entry against bytes that live anywhere in the source file.
 */
internal class DeltaScanner(private val source: ByteArray) {

  private val index = MatchIndex(source)
  private val match = Match()

  fun scan(target: ByteArray, from: Int, to: Int, writer: DeltaWriter) {
    var position = from
    var literalStart = from
    var hash = 0
    var hashPosition = NO_HASH

    while (position < to) {
      val runLength = runLengthAt(target, position, to)

      var matchOffset = -1
      var matchLength = 0
      if (position + RollingHash.WINDOW <= to) {
        hash = if (hashPosition == position - 1) {
          RollingHash.roll(
            hash,
            target[position - 1],
            target[position + RollingHash.WINDOW - 1]
          )
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
        position += matchLength
        literalStart = position
        hashPosition = NO_HASH
      } else if (runLength > 0) {
        writer.add(target, literalStart, position)
        writer.run(target[position], runLength)
        position += runLength
        literalStart = position
        hashPosition = NO_HASH
      } else {
        position++
      }
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

  private companion object {
    /** Sentinel that can never be `position - 1`, so the first hash is always computed in full. */
    const val NO_HASH = Int.MIN_VALUE
  }
}
