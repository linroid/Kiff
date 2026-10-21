@file:Suppress("DEPRECATION")

package com.linroid.kiff

import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.container.ZipFormat
import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.RollingHashAlgorithm
import com.linroid.kiff.region.RegionPlanner
import com.linroid.kiff.region.TextAwareRegionPlanner
import com.linroid.kiff.text.LineDiffAlgorithm
import com.linroid.kiff.text.MyersDiffAlgorithm
import com.linroid.kiff.zip.ZipDiffReport

/**
 * Takes its target to be a zip archive, entries, gaps and directory, whatever it is.
 *
 * [Kiff.createPatch] does exactly this for a readable zip that is not an Android package, and
 * falls back the same way when the source is not one.
 */
@Deprecated(
  "Kiff.createPatch detects what a file is, and describes a zip exactly as this does. " +
    "Patchers are removed in 0.3.0."
)
class ZipPatcher(
  override val algorithm: DeltaAlgorithm = RollingHashAlgorithm,
  /** Chooses how each leaf region is described; see [RegionPlanner]. */
  val planner: RegionPlanner = TextAwareRegionPlanner,
  /** Formats to look for *inside* the archive, so a zip within a zip is taken apart too. */
  val containers: ContainerRegistry = NestedContainers,
  val lineAlgorithm: LineDiffAlgorithm = MyersDiffAlgorithm()
) : DeltaPatcher() {

  override val id: PatcherId = PatcherId.ZIP
  override val name: String = "zip"

  override val options: KiffOptions = KiffOptions(algorithm, planner, containers, lineAlgorithm)

  override val structure: Structure = Structure.Archive(ZipFormat())

  /**
   * Reports what changed entry by entry, without building a patch.
   *
   * @throws KiffException.UnsupportedInput if either input is not a readable zip archive.
   */
  @Deprecated(
    "KiffReports.zip makes the same report.",
    ReplaceWith("KiffReports.zip(source, target)", "com.linroid.kiff.KiffReports")
  )
  fun analyze(source: ByteArray, target: ByteArray): ZipDiffReport =
    KiffReports.zip(source, target)
}
