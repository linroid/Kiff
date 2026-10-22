package com.linroid.kiff

import com.linroid.kiff.apk.ApkDiffReport
import com.linroid.kiff.apk.ApkEntries
import com.linroid.kiff.apk.ApkEntryKind
import com.linroid.kiff.apk.ApkPatchReport
import com.linroid.kiff.apk.analyzeApk
import com.linroid.kiff.apk.groupApkCost
import com.linroid.kiff.apk.looksLikeApk
import com.linroid.kiff.io.asSource
import com.linroid.kiff.region.RegionCost
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.region.RegionReport
import com.linroid.kiff.zip.ZipDiffReport
import com.linroid.kiff.zip.analyzeArchives

/**
 * What two files have in common, and what a patch between them costs, as data rather than as a
 * patch.
 *
 * [zip] and [apk] say what *changed*, and are cheap: they pair the entries without describing any
 * of them. [explain] says what the change *costs*, which is not the same question - a large entry
 * can change and still be nearly free, and a small one can be expensive - and has to build the
 * patch to answer it.
 */
public object KiffReports {

  /**
   * Reports what changed between two zip archives, entry by entry.
   *
   * @throws KiffException.UnsupportedInput if either input is not a readable zip archive.
   */
  public fun zip(source: ByteArray, target: ByteArray): ZipDiffReport =
    analyzeArchives(source, target)

  /**
   * Reports what changed between two Android packages, entry by entry and grouped by kind.
   *
   * @throws KiffException.UnsupportedInput if either input is not a readable zip archive.
   */
  public fun apk(source: ByteArray, target: ByteArray): ApkDiffReport = analyzeApk(source, target)

  /**
   * Builds the patch [Kiff.createPatch] would, and reports where its bytes went, region by region.
   *
   * A zip is carved into its entries, gaps and directory, a file of no known structure into a
   * single whole, so every patch can say what each of its regions cost. A region's cost is only
   * known once it has been described, so this does the full encode; the patch is discarded.
   */
  public fun explain(
    source: ByteArray,
    target: ByteArray,
    options: KiffOptions = KiffOptions()
  ): RegionReport = explainAs(source, target, options, structure = null)

  /**
   * [explain], with the regions grouped the way an Android package is built.
   *
   * Entry kinds are what makes the attribution actionable: knowing a patch is 80% dex says which
   * part of the encoder is worth work, where a list of entry names does not.
   */
  public fun explainApk(
    source: ByteArray,
    target: ByteArray,
    options: KiffOptions = KiffOptions()
  ): ApkPatchReport = groupApkCost(explain(source, target, options))

  /**
   * True when [bytes] is a zip archive that carries an `AndroidManifest.xml`: what
   * [Kiff.createPatch] takes for an APK.
   */
  public fun isApk(bytes: ByteArray): Boolean = looksLikeApk(bytes)

  /** The kind of entry [entryName] is in an Android package, as [apk] groups them. */
  public fun apkEntryKind(entryName: String): ApkEntryKind = ApkEntries.kindOf(entryName)

  /** [explain], with [structure] in place of detection when it is given. */
  internal fun explainAs(
    source: ByteArray,
    target: ByteArray,
    options: KiffOptions,
    structure: Structure?
  ): RegionReport {
    val regions = ArrayList<RegionCost>()
    val patch = PatchBuilder.build(
      source.asSource(),
      target.asSource(),
      options,
      RegionRecorder { cost -> regions.add(cost) },
      structure
    )
    return RegionReport(
      regions = regions,
      targetSize = target.size.toLong(),
      patchSize = patch.size.toLong()
    )
  }
}
