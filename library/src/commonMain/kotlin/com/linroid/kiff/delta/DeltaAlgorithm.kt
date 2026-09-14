package com.linroid.kiff.delta

/**
 * A delta algorithm: the search that decides how a target region is described in terms of a source
 * region.
 *
 * The instruction set it emits - COPY, DIFF, ADD, RUN - is fixed, so every algorithm's output is
 * decoded by the same [DeltaReader] and needs no marker in the patch header. Only the search
 * differs, which is why a patcher never has to know which algorithm it is driving, and why a new
 * algorithm can be added without touching the patch format.
 *
 * An algorithm prepares a [DeltaScanner] over a source range once, and the patchers reuse one
 * scanner for many target regions, so whatever index the search needs is built a single time.
 */
internal interface DeltaAlgorithm {

  val name: String

  /**
   * Prepares a scanner that describes target regions against `source[from, to)`.
   *
   * Only that range is indexed, but matches may reach anywhere in [source], so an archive patcher
   * can index a single entry and still emit copies pointing outside it.
   */
  fun scanner(source: ByteArray, from: Int = 0, to: Int = source.size): DeltaScanner
}

/** Describes target regions against the source range its [DeltaAlgorithm] indexed. */
internal interface DeltaScanner {

  /**
   * Describes `target[from, to)` by driving [writer].
   *
   * @param initialAlignment source offset that `target[from]` is expected to correspond to, minus
   *   [from]; archive patchers pass the offset of the matching entry so a changed entry can start
   *   out aligned.
   */
  fun scan(
    target: ByteArray,
    from: Int,
    to: Int,
    writer: DeltaWriter,
    initialAlignment: Int = 0
  )
}
