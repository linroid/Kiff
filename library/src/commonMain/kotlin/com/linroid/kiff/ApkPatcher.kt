@file:Suppress("DEPRECATION")

package com.linroid.kiff

import com.linroid.kiff.apk.ApkDiffReport
import com.linroid.kiff.apk.ApkEntryKind
import com.linroid.kiff.apk.ApkPatchReport
import com.linroid.kiff.apk.groupApkCost
import com.linroid.kiff.container.ApkFormat
import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.RollingHashAlgorithm
import com.linroid.kiff.region.RegionPlanner
import com.linroid.kiff.region.TextAwareRegionPlanner
import com.linroid.kiff.text.LineDiffAlgorithm
import com.linroid.kiff.text.MyersDiffAlgorithm

/**
 * Takes its target to be an Android package, whatever it is: a zip whose dex files are searched
 * together and paired by ordinal when a build renumbers them.
 *
 * [Kiff.createPatch] does exactly this for a readable zip that carries an `AndroidManifest.xml`.
 */
@Deprecated(
  "Kiff.createPatch detects what a file is, and describes an APK exactly as this does. " +
    "Patchers are removed in 0.3.0."
)
class ApkPatcher(
  override val algorithm: DeltaAlgorithm = RollingHashAlgorithm,
  /** Chooses how each leaf region is described; see [RegionPlanner]. */
  val planner: RegionPlanner = TextAwareRegionPlanner,
  /** Formats to look for *inside* the package, so a bundled archive is taken apart too. */
  val containers: ContainerRegistry = NestedContainers,
  val lineAlgorithm: LineDiffAlgorithm = MyersDiffAlgorithm()
) : DeltaPatcher() {

  override val id: PatcherId = PatcherId.APK
  override val name: String = "apk"

  override val options: KiffOptions = KiffOptions(algorithm, planner, containers, lineAlgorithm)

  override val structure: Structure = Structure.Archive(ApkFormat())

  /**
   * Reports what changed, entry by entry and grouped by kind, without building a patch.
   *
   * @throws KiffException.UnsupportedInput if either input is not a readable zip archive.
   */
  @Deprecated(
    "KiffReports.apk makes the same report.",
    ReplaceWith("KiffReports.apk(source, target)", "com.linroid.kiff.KiffReports")
  )
  fun analyze(source: ByteArray, target: ByteArray): ApkDiffReport =
    KiffReports.apk(source, target)

  /** [explain], with the regions grouped the way the package is actually built. */
  @Deprecated(
    "KiffReports.explainApk reports on the patch Kiff.createPatch builds.",
    ReplaceWith("KiffReports.explainApk(source, target)", "com.linroid.kiff.KiffReports")
  )
  fun explainKinds(source: ByteArray, target: ByteArray): ApkPatchReport =
    groupApkCost(KiffReports.explainAs(source, target, options, structure))

  /** True when [bytes] is a zip archive that carries an `AndroidManifest.xml`. */
  @Deprecated(
    "KiffReports.isApk answers the same question.",
    ReplaceWith("KiffReports.isApk(bytes)", "com.linroid.kiff.KiffReports")
  )
  fun isApk(bytes: ByteArray): Boolean = KiffReports.isApk(bytes)

  @Deprecated(
    "KiffReports.apkEntryKind answers the same question.",
    ReplaceWith("KiffReports.apkEntryKind(entryName)", "com.linroid.kiff.KiffReports")
  )
  fun kindOf(entryName: String): ApkEntryKind = KiffReports.apkEntryKind(entryName)
}
