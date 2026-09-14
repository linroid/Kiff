package com.linroid.kiff.delta

/**
 * Where a [DeltaScanner] writes what it found.
 *
 * The four instructions are the whole delta language, and the set is fixed: every algorithm emits
 * these and only these, which is why any algorithm's output is decoded by the same reader and why a
 * new algorithm needs no change to the patch format.
 *
 * A sink describes the target in order. The regions passed to [add], [copy], [diff] and [run] must
 * tile `target[from, to)` exactly, with no gap and no overlap, or the patch will not restore.
 */
interface DeltaSink {

  /** Literal bytes that could not be found in the source. */
  fun add(bytes: ByteArray, from: Int = 0, to: Int = bytes.size)

  /** A run of [length] bytes copied verbatim from the source at [sourceOffset]. */
  fun copy(sourceOffset: Long, length: Long)

  /**
   * A region that *almost* matches the source: the sink stores `target - source`, byte by byte,
   * reading the source itself.
   *
   * This is what keeps a patch small when a region is nearly a copy - recompiled code whose
   * embedded offsets shifted, say. The differences are mostly zero and collapse in the literal
   * stream, where the same region emitted through [add] would not compress at all.
   */
  fun diff(sourceOffset: Long, target: ByteArray, from: Int = 0, to: Int = target.size)

  /** [length] repetitions of a single byte. */
  fun run(value: Byte, length: Long)
}
