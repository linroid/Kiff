package com.linroid.kiff.zip

/**
 * Minimal zip structure reader: enough to locate every byte range of an archive, not to decompress
 * it. Returns `null` for anything it cannot describe exactly - a non-zip file, zip64, an archive
 * with overlapping records - so callers can fall back to a plain byte-level diff.
 */
internal object ZipReader {

  private const val EOCD_SIGNATURE = 0x06054B50
  private const val CENTRAL_SIGNATURE = 0x02014B50
  private const val LOCAL_SIGNATURE = 0x04034B50
  private const val DESCRIPTOR_SIGNATURE = 0x08074B50

  private const val EOCD_SIZE = 22
  private const val CENTRAL_HEADER_SIZE = 46
  private const val LOCAL_HEADER_SIZE = 30
  private const val MAX_COMMENT_SIZE = 0xFFFF
  private const val FLAG_DATA_DESCRIPTOR = 0x08
  private const val ZIP64_MARKER_16 = 0xFFFF
  private const val ZIP64_MARKER_32 = 0xFFFFFFFFL

  fun parseOrNull(bytes: ByteArray): ZipLayout? {
    val eocd = findEocd(bytes) ?: return null
    val entryCount = u16(bytes, eocd + 10)
    val directorySize = u32(bytes, eocd + 12)
    val directoryOffset = u32(bytes, eocd + 16)
    if (entryCount == ZIP64_MARKER_16 ||
      directorySize == ZIP64_MARKER_32 ||
      directoryOffset == ZIP64_MARKER_32
    ) {
      return null // zip64, which this reader deliberately does not claim to understand
    }
    if (directoryOffset > Int.MAX_VALUE || directorySize > Int.MAX_VALUE) return null
    val directoryStart = directoryOffset.toInt()
    if (directoryStart + directorySize > eocd) return null

    val entries = ArrayList<ZipEntry>(entryCount)
    var cursor = directoryStart
    repeat(entryCount) {
      val entry = readCentralEntry(bytes, cursor, eocd) ?: return null
      entries.add(entry.first)
      cursor = entry.second
    }
    if (cursor != directoryStart + directorySize.toInt()) return null

    entries.sortBy { it.localHeaderOffset }
    val regions = buildRegions(entries, directoryStart, bytes.size) ?: return null
    return ZipLayout(entries, regions, directoryStart)
  }

  private fun buildRegions(
    entries: List<ZipEntry>,
    directoryStart: Int,
    archiveSize: Int
  ): List<ZipRegion>? {
    val regions = ArrayList<ZipRegion>(entries.size * 2 + 2)
    var cursor = 0
    var previousName: String? = null
    for (entry in entries) {
      if (entry.localHeaderOffset < cursor) return null // overlapping records
      if (entry.localHeaderOffset > cursor) {
        val key = if (previousName == null) {
          ZipGapKey.PREAMBLE
        } else {
          ZipGapKey(afterEntry = previousName, beforeDirectory = false)
        }
        regions.add(ZipRegion.Gap(cursor, entry.localHeaderOffset, key))
      }
      regions.add(ZipRegion.Record(entry))
      cursor = entry.recordEnd
      previousName = entry.name
    }
    if (cursor > directoryStart) return null
    if (cursor < directoryStart) {
      regions.add(ZipRegion.Gap(cursor, directoryStart, ZipGapKey.BEFORE_DIRECTORY))
    }
    regions.add(ZipRegion.Directory(directoryStart, archiveSize))
    return regions
  }

  /** Returns the entry and the offset of the next central directory header. */
  private fun readCentralEntry(bytes: ByteArray, at: Int, limit: Int): Pair<ZipEntry, Int>? {
    if (at < 0 || at + CENTRAL_HEADER_SIZE > limit) return null
    if (u32(bytes, at) != CENTRAL_SIGNATURE.toLong()) return null
    val flags = u16(bytes, at + 8)
    val method = u16(bytes, at + 10)
    val crc32 = u32(bytes, at + 16).toUInt()
    val compressedSize = u32(bytes, at + 20)
    val uncompressedSize = u32(bytes, at + 24)
    val nameLength = u16(bytes, at + 28)
    val extraLength = u16(bytes, at + 30)
    val commentLength = u16(bytes, at + 32)
    val localOffset = u32(bytes, at + 42)
    val next = at + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
    if (next > limit) return null
    if (compressedSize == ZIP64_MARKER_32 ||
      uncompressedSize == ZIP64_MARKER_32 ||
      localOffset == ZIP64_MARKER_32
    ) {
      return null
    }
    if (compressedSize > Int.MAX_VALUE ||
      uncompressedSize > Int.MAX_VALUE ||
      localOffset > Int.MAX_VALUE
    ) {
      return null
    }

    val name = bytes.decodeToString(at + CENTRAL_HEADER_SIZE, at + CENTRAL_HEADER_SIZE + nameLength)
    val localHeaderOffset = localOffset.toInt()
    if (localHeaderOffset + LOCAL_HEADER_SIZE > limit) return null
    if (u32(bytes, localHeaderOffset) != LOCAL_SIGNATURE.toLong()) return null
    val localNameLength = u16(bytes, localHeaderOffset + 26)
    val localExtraLength = u16(bytes, localHeaderOffset + 28)
    val dataOffset = localHeaderOffset + LOCAL_HEADER_SIZE + localNameLength + localExtraLength
    val dataEnd = dataOffset.toLong() + compressedSize
    if (dataEnd > limit) return null

    var recordEnd = dataEnd.toInt()
    if (flags and FLAG_DATA_DESCRIPTOR != 0) {
      val descriptorSize = when {
        recordEnd + 16 <= limit && u32(bytes, recordEnd) == DESCRIPTOR_SIGNATURE.toLong() -> 16
        recordEnd + 12 <= limit -> 12
        else -> return null
      }
      recordEnd += descriptorSize
      if (recordEnd > limit) return null
    }

    val entry = ZipEntry(
      name = name,
      method = method,
      crc32 = crc32,
      compressedSize = compressedSize.toInt(),
      uncompressedSize = uncompressedSize.toInt(),
      localHeaderOffset = localHeaderOffset,
      dataOffset = dataOffset,
      recordEnd = recordEnd
    )
    return entry to next
  }

  private fun findEocd(bytes: ByteArray): Int? {
    if (bytes.size < EOCD_SIZE) return null
    val lowest = maxOf(0, bytes.size - EOCD_SIZE - MAX_COMMENT_SIZE)
    for (at in bytes.size - EOCD_SIZE downTo lowest) {
      if (u32(bytes, at) != EOCD_SIGNATURE.toLong()) continue
      val commentLength = u16(bytes, at + 20)
      if (at + EOCD_SIZE + commentLength == bytes.size) return at
    }
    return null
  }

  private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

  private fun u32(bytes: ByteArray, at: Int): Long =
    (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
}
