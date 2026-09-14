package com.linroid.kiff.zip

internal class ZipLayout(
  val entries: List<ZipEntry>,
  /** Every byte of the archive, in order, with no gaps and no overlaps. */
  val regions: List<ZipRegion>,
  val directoryStart: Int
) {
  val entriesByName: Map<String, ZipEntry> = entries.associateBy { it.name }

  /** Only entries with a unique content fingerprint, used to pair renamed entries. */
  val entriesByContent: Map<ContentKey, ZipEntry> = run {
    val unique = HashMap<ContentKey, ZipEntry>(entries.size)
    val duplicated = HashSet<ContentKey>()
    for (entry in entries) {
      val key = ContentKey(entry.crc32, entry.compressedSize, entry.method)
      if (!duplicated.add(key)) continue
      if (unique.put(key, entry) != null) unique.remove(key)
    }
    unique
  }

  val gapsByKey: Map<ZipGapKey, ZipRegion.Gap> =
    regions.filterIsInstance<ZipRegion.Gap>().associateBy { it.key }

  val directory: ZipRegion.Directory = regions.filterIsInstance<ZipRegion.Directory>().first()

  data class ContentKey(val crc32: UInt, val compressedSize: Int, val method: Int)
}
