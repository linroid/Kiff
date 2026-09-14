package com.linroid.kiff.zip

import com.linroid.kiff.delta.DeltaScanner
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.delta.MatchIndex

/**
 * Describes a target archive region by region.
 *
 * The point of walking the structure instead of the raw bytes is that every target region gets
 * compared against the region it actually corresponds to: an unchanged entry becomes a single copy
 * without being scanned at all, and a changed entry is indexed against its counterpart alone, which
 * buys a finer stride and far fewer hash collisions than indexing the whole archive.
 */
internal class ZipEncoder(
  private val source: ByteArray,
  private val sourceLayout: ZipLayout,
  private val target: ByteArray,
  private val targetLayout: ZipLayout,
  private val options: ZipEncodeOptions
) {

  /** Built only if some target region has no counterpart to be indexed against. */
  private val wholeSourceIndex by lazy { MatchIndex(source) }

  fun encode(writer: DeltaWriter) {
    for (region in targetLayout.regions) {
      when (region) {
        is ZipRegion.Record -> encodeRecord(region.entry, writer)
        is ZipRegion.Gap -> encodeGap(region, writer)
        is ZipRegion.Directory -> encodeAgainst(
          sourceLayout.directory.from,
          sourceLayout.directory.to,
          region.from,
          region.to,
          writer
        )
      }
    }
  }

  private fun encodeRecord(entry: ZipEntry, writer: DeltaWriter) {
    val counterpart = counterpartOf(entry)
    if (counterpart == null) {
      // Searching the whole archive means indexing it; only worth it for a substantial entry.
      if (entry.recordEnd - entry.localHeaderOffset < MIN_SEARCHABLE_RECORD) {
        writer.add(target, entry.localHeaderOffset, entry.recordEnd)
      } else {
        scan(wholeSourceIndex, entry.localHeaderOffset, entry.recordEnd, writer, alignment = 0)
      }
      return
    }

    val recordSize = entry.recordEnd - entry.localHeaderOffset
    if (counterpart.recordEnd - counterpart.localHeaderOffset == recordSize &&
      regionsEqual(counterpart.localHeaderOffset, entry.localHeaderOffset, recordSize)
    ) {
      writer.copy(counterpart.localHeaderOffset, recordSize)
      return
    }

    val dataUnchanged = counterpart.compressedSize == entry.compressedSize &&
      regionsEqual(counterpart.dataOffset, entry.dataOffset, entry.compressedSize)
    if (dataUnchanged) {
      // Only metadata moved, typically a timestamp or an alignment pad in the extra field.
      encodeAgainst(
        counterpart.localHeaderOffset,
        counterpart.dataOffset,
        entry.localHeaderOffset,
        entry.dataOffset,
        writer
      )
      writer.copy(counterpart.dataOffset, entry.compressedSize)
      encodeAgainst(
        counterpart.dataOffset + counterpart.compressedSize,
        counterpart.recordEnd,
        entry.dataOffset + entry.compressedSize,
        entry.recordEnd,
        writer
      )
      return
    }

    encodeAgainst(
      counterpart.localHeaderOffset,
      counterpart.recordEnd,
      entry.localHeaderOffset,
      entry.recordEnd,
      writer
    )
  }

  private fun encodeGap(gap: ZipRegion.Gap, writer: DeltaWriter) {
    val counterpart = sourceLayout.gapsByKey[gap.key]
    if (counterpart == null) {
      writer.add(target, gap.from, gap.to)
    } else {
      encodeAgainst(counterpart.from, counterpart.to, gap.from, gap.to, writer)
    }
  }

  /** Indexes `source[sourceFrom, sourceTo)` and describes `target[targetFrom, targetTo)`. */
  private fun encodeAgainst(
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    writer: DeltaWriter
  ) {
    if (targetTo <= targetFrom) return
    val span = targetTo - targetFrom
    if (span == sourceTo - sourceFrom && regionsEqual(sourceFrom, targetFrom, span)) {
      writer.copy(sourceFrom, span)
      return
    }
    val index = MatchIndex(source, sourceFrom, sourceTo)
    scan(index, targetFrom, targetTo, writer, alignment = sourceFrom - targetFrom)
  }

  /**
   * [alignment] tells the scanner which source offset this target region starts out parallel to, so
   * a changed entry can be difference-encoded from its counterpart's first byte.
   */
  private fun scan(
    index: MatchIndex,
    targetFrom: Int,
    targetTo: Int,
    writer: DeltaWriter,
    alignment: Int
  ) {
    DeltaScanner(index).scan(target, targetFrom, targetTo, writer, alignment)
  }

  private fun counterpartOf(entry: ZipEntry): ZipEntry? =
    sourceLayout.entriesByName[entry.name]
      ?: sourceLayout.entriesByContent[
        ZipLayout.ContentKey(entry.crc32, entry.compressedSize, entry.method)
      ]
      ?: options.pairUnmatched(entry, sourceLayout)

  private fun regionsEqual(sourceFrom: Int, targetFrom: Int, length: Int): Boolean {
    if (sourceFrom < 0 || sourceFrom + length > source.size) return false
    if (targetFrom + length > target.size) return false
    for (i in 0 until length) {
      if (source[sourceFrom + i] != target[targetFrom + i]) return false
    }
    return true
  }

  private companion object {
    const val MIN_SEARCHABLE_RECORD = 64 * 1024
  }
}

/** Format-specific knowledge a [ZipEncoder] can be given. */
internal class ZipEncodeOptions(
  /**
   * Last-resort pairing for a target entry that matched no source entry by name or by content -
   * where an APK can say that `classes4.dex` belongs next to `classes3.dex`.
   */
  val pairUnmatched: (ZipEntry, ZipLayout) -> ZipEntry? = { _, _ -> null }
)
