package com.linroid.kiff.delta

import com.linroid.kiff.io.SeekableSource

/** Describes target regions against the source range its [DeltaAlgorithm] indexed. */
interface DeltaScanner : AutoCloseable {

  /**
   * Describes `target[from, to)` by driving [sink].
   *
   * @param initialAlignment source offset that `target[from]` is expected to correspond to, minus
   *   [from]; archive patchers pass the offset of the matching entry so a changed entry can start
   *   out aligned. Pass 0 when nothing is known.
   */
  fun scan(
    target: SeekableSource,
    from: Long,
    to: Long,
    sink: DeltaSink,
    initialAlignment: Long = 0
  )

  override fun close() {}
}
