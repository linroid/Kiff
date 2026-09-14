package com.linroid.kiff.delta

import com.linroid.kiff.io.SeekableSource

/**
 * A delta algorithm: the search that decides how a target region is described in terms of a source
 * region.
 *
 * This is the extension point. A patcher decides *which* regions of two files are worth comparing;
 * an algorithm decides how the bytes inside a region are matched. Because the instruction set it
 * emits is fixed - see [DeltaSink] - an algorithm is an encode-side choice alone: the same reader
 * decodes every patch, so a custom algorithm needs no change to the patch format and interoperates
 * with patches Kiff itself produced.
 *
 * An algorithm prepares a [DeltaScanner] over a source range once, and the patchers reuse a single
 * scanner across many target regions, so whatever index the search needs is built one time.
 */
interface DeltaAlgorithm {

  /** Short identifier, for diagnostics; it is not recorded in the patch. */
  val name: String

  /**
   * Prepares a scanner that describes target regions against `source[from, to)`.
   *
   * Only that range need be indexed, but a match may reach anywhere in [source], so an archive
   * patcher can index a single entry and still emit copies pointing outside it.
   */
  fun scanner(source: SeekableSource, from: Long = 0, to: Long = source.size): DeltaScanner
}

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
