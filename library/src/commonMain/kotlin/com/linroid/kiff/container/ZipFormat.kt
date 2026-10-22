package com.linroid.kiff.container

import com.linroid.kiff.apk.ApkEntries
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readBytes
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.zip.ZipGapKey
import com.linroid.kiff.zip.ZipReader
import com.linroid.kiff.zip.ZipRegion
import kotlin.math.abs

/**
 * Zip archives, taken apart into their entries, the gaps between them, and the directory.
 *
 * The gaps are the part worth noticing. A zip is not only its entries: a self-extracting archive
 * carries a preamble, builds pad for alignment, and an APK keeps its signing block in the unnamed
 * run before the central directory. All of it has to be named for a patch to restore, so all of it
 * is a child.
 *
 * [groupOf] names the group an entry belongs to, by its name, for [ApkFormat]; a plain zip knows
 * of none.
 */
public class ZipFormat internal constructor(
  private val groupOf: (name: String) -> String?
) : ContainerFormat {

  public constructor() : this({ null })

  override val name: String get() = "zip"

  /**
   * A local header at the start, or the end-of-central-directory of an empty archive.
   *
   * Deliberately four bytes and no more. This is asked of every region that might be a container,
   * so it has to be cheap, and being wrong is recoverable: [decompose] answers an empty list for
   * anything it cannot describe exactly, and the range is then an ordinary leaf.
   */
  override fun detect(input: SeekableSource, from: Long, to: Long): Boolean {
    if (to - from < MIN_ARCHIVE) return false
    val magic = input.readBytes(from, LOCAL_HEADER.size)
    return magic.contentEquals(LOCAL_HEADER) || magic.contentEquals(EMPTY_ARCHIVE)
  }

  /**
   * Reads the archive's end of central directory, its directory and each local header, and none
   * of its entries' data.
   */
  override fun decompose(input: SeekableSource, from: Long, to: Long): List<Child> {
    // Offsets inside a zip are relative to the archive's own start, so a nested one is read in its
    // own coordinates and translated back.
    val layout = ZipReader.parseOrNull(input, from, to) ?: return emptyList()

    return layout.regions.map { region ->
      when (region) {
        is ZipRegion.Record -> {
          val entry = region.entry
          Child(
            name = entry.name,
            kind = RegionKind.CONTENT,
            from = from + entry.localHeaderOffset,
            to = from + entry.recordEnd,
            contentFrom = from + entry.dataOffset,
            contentTo = from + entry.dataOffset + entry.compressedSize,
            storage = if (entry.isStored) Storage.STORED else Storage.DEFLATED,
            contentKey = ContentKey(entry.crc32, entry.compressedSize, entry.method),
            group = groupOf(entry.name)
          )
        }
        is ZipRegion.Gap -> Child(
          name = labelOfGap(region.key),
          kind = RegionKind.GAP,
          from = from + region.from,
          to = from + region.to
        )
        is ZipRegion.Directory -> Child(
          name = CENTRAL_DIRECTORY,
          kind = RegionKind.INDEX,
          from = from + region.from,
          to = from + region.to
        )
      }
    }
  }

  private fun labelOfGap(key: ZipGapKey): String = when {
    key.beforeDirectory -> BEFORE_DIRECTORY
    key.afterEntry == null -> PREAMBLE
    else -> "(gap after ${key.afterEntry})"
  }

  /** What makes two entries the same content without comparing their bytes. */
  private data class ContentKey(val crc32: UInt, val compressedSize: Int, val method: Int)

  internal companion object {
    const val CENTRAL_DIRECTORY = "(central directory)"
    const val PREAMBLE = "(preamble)"

    /** In an APK this is where the signing block sits. */
    const val BEFORE_DIRECTORY = "(gap before central directory)"

    private const val MIN_ARCHIVE = 22
    private val LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val EMPTY_ARCHIVE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
  }
}

/**
 * Android packages: a zip, plus the one thing a zip cannot know.
 *
 * A build that gains or loses a dex renumbers the rest, so `classes4.dex` can have no counterpart
 * by name and no counterpart by content, and still be far closer to the source's highest-numbered
 * dex than to nothing at all.
 *
 * It reads an APK exactly as [ZipFormat] reads any zip, and adds only its grouping and pairing of
 * dex files.
 */
public class ApkFormat : ContainerFormat {

  private val zip = ZipFormat(::groupOf)

  override val name: String get() = "apk"

  override fun detect(input: SeekableSource, from: Long, to: Long): Boolean =
    zip.detect(input, from, to)

  override fun decompose(input: SeekableSource, from: Long, to: Long): List<Child> =
    zip.decompose(input, from, to)

  /**
   * Every dex is one group.
   *
   * A rebuild repartitions classes across them - on two real builds of one app the split went from
   * 3.4 MB + 7.2 MB to 5.5 MB + 5.1 MB, roughly two megabytes of classes changing file while
   * changing nothing else. Searching each dex only against the dex of the same name cannot find
   * any of it.
   */
  private fun groupOf(name: String): String? =
    if (ApkEntries.dexOrdinal(name) != null) DEX_GROUP else null

  override fun pairUnmatched(target: Child, source: List<Child>): Child? {
    val ordinal = ApkEntries.dexOrdinal(target.name) ?: return null
    var best: Child? = null
    var bestDistance = Int.MAX_VALUE
    for (candidate in source) {
      val candidateOrdinal = ApkEntries.dexOrdinal(candidate.name) ?: continue
      val distance = abs(candidateOrdinal - ordinal)
      if (distance < bestDistance) {
        bestDistance = distance
        best = candidate
      }
    }
    return best
  }

  private companion object {
    const val DEX_GROUP = "dex"
  }
}
