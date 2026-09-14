package com.linroid.kiff.zip

import com.linroid.kiff.KiffException
import com.linroid.kiff.ZipDiffReport
import com.linroid.kiff.ZipEntryChange
import com.linroid.kiff.ZipEntryStatus
import com.linroid.kiff.delta.DeltaScanner
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.delta.MatchIndex

/**
 * Shared by the zip and apk algorithms: describe [target] using [source], walking the archive
 * structure when both files are readable archives and falling back to a plain byte-level scan when
 * they are not, so a patch is always produced.
 */
internal fun encodeArchive(source: ByteArray, target: ByteArray, writer: DeltaWriter) {
  val sourceLayout = ZipReader.parseOrNull(source)
  val targetLayout = ZipReader.parseOrNull(target)
  if (sourceLayout == null || targetLayout == null) {
    DeltaScanner(MatchIndex(source)).scan(target, 0, target.size, writer)
    return
  }
  ZipEncoder(source, sourceLayout, target, targetLayout).encode(writer)
}

internal fun analyzeArchives(source: ByteArray, target: ByteArray): ZipDiffReport {
  val sourceLayout = ZipReader.parseOrNull(source)
    ?: throw KiffException.UnsupportedInput("Source is not a readable zip archive")
  val targetLayout = ZipReader.parseOrNull(target)
    ?: throw KiffException.UnsupportedInput("Target is not a readable zip archive")

  val changes = ArrayList<ZipEntryChange>(targetLayout.entries.size + 8)
  for (entry in targetLayout.entries) {
    val counterpart = sourceLayout.entriesByName[entry.name]
    val status = when {
      counterpart == null -> ZipEntryStatus.ADDED
      recordsEqual(source, counterpart, target, entry) -> ZipEntryStatus.UNCHANGED
      dataEqual(source, counterpart, target, entry) -> ZipEntryStatus.METADATA_CHANGED
      else -> ZipEntryStatus.MODIFIED
    }
    changes.add(
      ZipEntryChange(
        name = entry.name,
        status = status,
        sourceSize = counterpart?.compressedSize ?: 0,
        targetSize = entry.compressedSize,
        stored = entry.isStored
      )
    )
  }
  for (entry in sourceLayout.entries) {
    if (entry.name in targetLayout.entriesByName) continue
    changes.add(
      ZipEntryChange(
        name = entry.name,
        status = ZipEntryStatus.REMOVED,
        sourceSize = entry.compressedSize,
        targetSize = 0,
        stored = entry.isStored
      )
    )
  }
  return ZipDiffReport(changes, source.size, target.size)
}

private fun recordsEqual(
  source: ByteArray,
  sourceEntry: ZipEntry,
  target: ByteArray,
  targetEntry: ZipEntry
): Boolean {
  val length = targetEntry.recordEnd - targetEntry.localHeaderOffset
  if (sourceEntry.recordEnd - sourceEntry.localHeaderOffset != length) return false
  val sourceFrom = sourceEntry.localHeaderOffset
  return rangesEqual(source, sourceFrom, target, targetEntry.localHeaderOffset, length)
}

private fun dataEqual(
  source: ByteArray,
  sourceEntry: ZipEntry,
  target: ByteArray,
  targetEntry: ZipEntry
): Boolean {
  if (sourceEntry.compressedSize != targetEntry.compressedSize) return false
  val size = targetEntry.compressedSize
  return rangesEqual(source, sourceEntry.dataOffset, target, targetEntry.dataOffset, size)
}

private fun rangesEqual(
  a: ByteArray,
  aFrom: Int,
  b: ByteArray,
  bFrom: Int,
  length: Int
): Boolean {
  if (aFrom + length > a.size || bFrom + length > b.size) return false
  for (i in 0 until length) {
    if (a[aFrom + i] != b[bFrom + i]) return false
  }
  return true
}
