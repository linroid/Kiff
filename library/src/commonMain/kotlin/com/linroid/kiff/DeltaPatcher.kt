@file:Suppress("DEPRECATION")

package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.region.RegionReport

/**
 * Base class for the bundled patchers, each of which always takes its target to be one thing.
 *
 * They build through the same path as [Kiff.createPatch], with that structure in place of what
 * detection would find, so a patcher writes exactly the patch it wrote before detection existed.
 */
@Deprecated(
  "Kiff.createPatch detects what a file is, and Kiff.applyPatch applies any patch. " +
    "Patchers are removed in 0.3.0."
)
sealed class DeltaPatcher : Patcher {

  /** The delta algorithm this patcher drives. */
  abstract val algorithm: DeltaAlgorithm

  internal abstract val options: KiffOptions

  internal abstract val structure: Structure

  @Deprecated(
    "Kiff.createPatch detects what the target is, so on an archive it may describe it differently.",
    ReplaceWith("Kiff.createPatch(source, target)", "com.linroid.kiff.Kiff")
  )
  final override fun createPatch(source: SeekableSource, target: SeekableSource): ByteArray =
    PatchBuilder.build(source, target, options, structure = structure)

  @Deprecated(
    "Kiff.applyPatch applies any patch.",
    ReplaceWith("Kiff.applyPatch(source, patch)", "com.linroid.kiff.Kiff")
  )
  final override fun applyPatch(source: SeekableSource, patch: ByteArray): ByteArray =
    PatchRestorer.apply(source, patch)

  @Deprecated(
    "Kiff.applyPatch applies any patch.",
    ReplaceWith("Kiff.applyPatch(source, patch, target)", "com.linroid.kiff.Kiff")
  )
  final override fun applyPatch(source: SeekableSource, patch: ByteArray, target: RestoreTarget) {
    PatchRestorer.apply(source, patch, target)
  }

  @Deprecated(
    "Kiff.createPatch detects what the target is, so on an archive it may describe it differently.",
    ReplaceWith("Kiff.createPatch(source, target)", "com.linroid.kiff.Kiff")
  )
  final override fun createPatch(source: ByteArray, target: ByteArray): ByteArray =
    createPatch(source.asSource(), target.asSource())

  @Deprecated(
    "Kiff.applyPatch applies any patch.",
    ReplaceWith("Kiff.applyPatch(source, patch)", "com.linroid.kiff.Kiff")
  )
  final override fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray =
    applyPatch(source.asSource(), patch)

  /** Builds this patcher's patch and reports where its bytes went, region by region. */
  @Deprecated(
    "KiffReports.explain reports on the patch Kiff.createPatch builds.",
    ReplaceWith("KiffReports.explain(source, target)", "com.linroid.kiff.KiffReports")
  )
  fun explain(source: ByteArray, target: ByteArray): RegionReport =
    KiffReports.explainAs(source, target, options, structure)
}
