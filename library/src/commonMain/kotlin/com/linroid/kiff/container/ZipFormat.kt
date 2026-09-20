package com.linroid.kiff.container

import com.linroid.kiff.apk.ApkEntries
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
 */
open class ZipFormat : ContainerFormat {

  override val name: String get() = "zip"

  /**
   * A local header at the start, or the end-of-central-directory of an empty archive.
   *
   * Deliberately four bytes and no more. This is asked of every region that might be a container,
   * so it has to be cheap, and being wrong is recoverable: [decompose] answers an empty list for
   * anything it cannot describe exactly, and the range is then an ordinary leaf.
   */
  override fun detect(bytes: ByteArray, from: Int, to: Int): Boolean {
    if (to - from < MIN_ARCHIVE) return false
    return startsWith(bytes, from, LOCAL_HEADER) || startsWith(bytes, from, EMPTY_ARCHIVE)
  }

  override fun decompose(bytes: ByteArray, from: Int, to: Int): List<Child> {
    // Offsets inside a zip are relative to the archive's own start, so a nested one has to be read
    // in its own coordinates and translated back.
    val whole = from == 0 && to == bytes.size
    val slice = if (whole) bytes else bytes.copyOfRange(from, to)
    val layout = ZipReader.parseOrNull(slice) ?: return emptyList()

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
            contentKey = ContentKey(entry.crc32, entry.compressedSize, entry.method)
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

  private fun startsWith(bytes: ByteArray, at: Int, signature: ByteArray): Boolean {
    if (at + signature.size > bytes.size) return false
    for (i in signature.indices) if (bytes[at + i] != signature[i]) return false
    return true
  }

  /** What makes two entries the same content without comparing their bytes. */
  private data class ContentKey(val crc32: UInt, val compressedSize: Int, val method: Int)

  companion object {
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
 */
class ApkFormat : ZipFormat() {

  override val name: String get() = "apk"

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
}
