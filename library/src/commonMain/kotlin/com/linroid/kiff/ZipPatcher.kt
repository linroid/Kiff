package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.delta.RollingHashAlgorithm
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.region.RegionPlanner
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.region.TextAwareRegionPlanner
import com.linroid.kiff.zip.ZipDiffReport
import com.linroid.kiff.zip.ZipEncodeOptions
import com.linroid.kiff.zip.analyzeArchives
import com.linroid.kiff.zip.encodeArchive

/**
 * Structure-aware diff for zip archives.
 *
 * It reads both archives' layouts and compares each target region against the region it
 * corresponds to - entries paired by name, or by content fingerprint when an entry was renamed - so
 * an unchanged entry costs one copy instruction and a changed entry is compared only against its
 * counterpart.
 *
 * Entry data is never recompressed, which is what makes a restore byte-exact; the flip side is that
 * a patch for a *deflated* entry can only be as small as the change in its compressed bytes.
 *
 * Falls back to a whole-file byte scan when either input is not a readable zip.
 */
class ZipPatcher(
  override val algorithm: DeltaAlgorithm = RollingHashAlgorithm,
  /** Chooses how each leaf region is described; see [RegionPlanner]. */
  val planner: RegionPlanner = TextAwareRegionPlanner
) : DeltaPatcher() {

  override val id: PatcherId = PatcherId.ZIP
  override val name: String = "zip"

  override fun encode(
    source: ByteArraySource,
    target: ByteArraySource,
    literals: ByteWriter,
    recorder: RegionRecorder?
  ): RegionNode = encodeArchive(
    source,
    target,
    ZipEncodeOptions(planner = planner),
    literals,
    algorithm,
    recorder
  )

  /**
   * Reports what changed entry by entry, without building a patch.
   *
   * @throws KiffException.UnsupportedInput if either input is not a readable zip archive.
   */
  fun analyze(source: ByteArray, target: ByteArray): ZipDiffReport = analyzeArchives(source, target)
}
