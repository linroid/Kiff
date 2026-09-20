package com.linroid.kiff

import com.linroid.kiff.apk.ApkDiffReport
import com.linroid.kiff.apk.ApkEntries
import com.linroid.kiff.apk.ApkEntryKind
import com.linroid.kiff.apk.ApkPatchReport
import com.linroid.kiff.apk.analyzeApk
import com.linroid.kiff.apk.apkEncodeOptions
import com.linroid.kiff.apk.groupApkCost
import com.linroid.kiff.apk.looksLikeApk
import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.delta.RollingHashAlgorithm
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.zip.encodeArchive

/**
 * Structure-aware diff for Android packages.
 *
 * An APK is a zip, so this builds on the same region-by-region encoding, and adds what is specific
 * to the format: dex files are paired by ordinal when a build renumbers them, the signing block
 * between the last entry and the central directory is reproduced as its own region, and
 * [analyze] groups the differences the way the package is actually built.
 *
 * Like [ZipPatcher] it never recompresses, which is what makes a restore byte-exact. That matters
 * more here than for zips in general: current Android builds store `.dex`, `.so` and
 * `resources.arsc` uncompressed, so the entries that dominate an APK are compared as their real
 * content.
 */
class ApkPatcher(
  override val algorithm: DeltaAlgorithm = RollingHashAlgorithm
) : DeltaPatcher() {

  override val id: PatcherId = PatcherId.APK
  override val name: String = "apk"

  override fun encode(
    source: ByteArraySource,
    target: ByteArraySource,
    sink: DeltaWriter,
    recorder: RegionRecorder?
  ) {
    encodeArchive(source, target, apkEncodeOptions(), sink, algorithm, recorder)
  }

  /**
   * Reports what changed, entry by entry and grouped by kind, without building a patch.
   *
   * @throws KiffException.UnsupportedInput if either input is not a readable zip archive.
   */
  fun analyze(source: ByteArray, target: ByteArray): ApkDiffReport = analyzeApk(source, target)

  /**
   * [explain], with the regions grouped the way the package is actually built.
   *
   * Entry kinds are what makes the attribution actionable: knowing a patch is 80% dex says which
   * part of the encoder is worth work, where a list of entry names does not.
   */
  fun explainKinds(source: ByteArray, target: ByteArray): ApkPatchReport =
    groupApkCost(explain(source, target))

  /** True when [bytes] is a zip archive that carries an `AndroidManifest.xml`. */
  fun isApk(bytes: ByteArray): Boolean = looksLikeApk(bytes)

  fun kindOf(entryName: String): ApkEntryKind = ApkEntries.kindOf(entryName)
}
