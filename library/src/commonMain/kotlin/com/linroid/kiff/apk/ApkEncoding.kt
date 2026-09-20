package com.linroid.kiff.apk

import com.linroid.kiff.zip.ZipEntryStatus
import com.linroid.kiff.zip.ZipEncodeOptions
import com.linroid.kiff.zip.ZipEntry
import com.linroid.kiff.zip.ZipLayout
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.region.RegionReport
import com.linroid.kiff.zip.ZipReader
import com.linroid.kiff.zip.analyzeLayouts
import com.linroid.kiff.zip.parseArchive
import kotlin.math.abs

internal fun apkEncodeOptions() = ZipEncodeOptions(pairUnmatched = ::pairDexByOrdinal)

/**
 * A build that gains or loses a dex file renumbers the rest, so a `classes4.dex` with no
 * counterpart is still far closer to the source's highest-numbered dex than to nothing.
 */
private fun pairDexByOrdinal(entry: ZipEntry, sourceLayout: ZipLayout): ZipEntry? {
  val ordinal = ApkEntries.dexOrdinal(entry.name) ?: return null
  var best: ZipEntry? = null
  var bestDistance = Int.MAX_VALUE
  for (candidate in sourceLayout.entries) {
    val candidateOrdinal = ApkEntries.dexOrdinal(candidate.name) ?: continue
    val distance = abs(candidateOrdinal - ordinal)
    if (distance < bestDistance) {
      bestDistance = distance
      best = candidate
    }
  }
  return best
}

internal fun analyzeApk(source: ByteArray, target: ByteArray): ApkDiffReport {
  val sourceLayout = parseArchive(source, "Source")
  val targetLayout = parseArchive(target, "Target")
  val entries = analyzeLayouts(source, sourceLayout, target, targetLayout)

  val kinds = entries.changes
    .groupBy { ApkEntries.kindOf(it.name) }
    .map { (kind, changes) ->
      ApkKindSummary(
        kind = kind,
        entryCount = changes.count { it.status != ZipEntryStatus.REMOVED },
        changedCount = changes.count {
          it.status == ZipEntryStatus.MODIFIED || it.status == ZipEntryStatus.METADATA_CHANGED
        },
        addedCount = changes.count { it.status == ZipEntryStatus.ADDED },
        removedCount = changes.count { it.status == ZipEntryStatus.REMOVED },
        sourceBytes = changes.sumOf { it.sourceSize.toLong() },
        targetBytes = changes.sumOf { it.targetSize.toLong() }
      )
    }
    .sortedWith(compareByDescending<ApkKindSummary> { it.targetBytes }.thenBy { it.kind.ordinal })

  return ApkDiffReport(
    entries = entries,
    kinds = kinds,
    sourceSigningBlockSize = ApkSigningBlock.sizeOf(source, sourceLayout.directoryStart),
    targetSigningBlockSize = ApkSigningBlock.sizeOf(target, targetLayout.directoryStart)
  )
}

/** Groups a region-by-region attribution the way an APK is actually built. */
internal fun groupApkCost(report: RegionReport): ApkPatchReport {
  val kinds = report.regions
    .filter { it.kind == RegionKind.CONTENT }
    .groupBy { ApkEntries.kindOf(it.name) }
    .map { (kind, costs) ->
      ApkKindCost(
        kind = kind,
        entryCount = costs.size,
        targetBytes = costs.sumOf { it.targetBytes },
        instructionBytes = costs.sumOf { it.instructionBytes },
        literalBytes = costs.sumOf { it.literalBytes }
      )
    }
    .sortedWith(compareByDescending<ApkKindCost> { it.patchBytes }.thenBy { it.kind.ordinal })

  return ApkPatchReport(
    regions = report,
    kinds = kinds,
    gapBytes = report.bytes(RegionKind.GAP),
    directoryBytes = report.bytes(RegionKind.INDEX)
  )
}

/** True when the archive looks like an Android package rather than a plain zip. */
internal fun looksLikeApk(bytes: ByteArray): Boolean {
  val layout = ZipReader.parseOrNull(bytes) ?: return false
  return "AndroidManifest.xml" in layout.entriesByName
}
