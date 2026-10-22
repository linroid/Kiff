package com.linroid.kiff.zip

import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.io.readBytes

/**
 * Minimal zip structure reader: enough to locate every byte range of an archive, not to decompress
 * it. Returns `null` for anything it cannot describe exactly - a non-zip file, zip64, an archive
 * with overlapping records - so callers can fall back to a plain byte-level diff.
 *
 * It reads where the structure is and nowhere else: the end of central directory, the directory,
 * and the fixed part of each local header. An entry's data is located, never read.
 *
 * Every offset and length it reads comes from the archive, which may be anything at all, so no
 * bound here is checked as an Int sum: an offset near Int.MAX_VALUE would wrap it negative and past
 * the check it was meant to fail. Either the sum is taken in Long or the check is a subtraction.
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

  fun parseOrNull(bytes: ByteArray): ZipLayout? = parseOrNull(bytes.asSource())

  /**
   * Reads the archive at `[from, to)` of [input] in its own coordinates: every offset in the layout
   * counts from [from], as the archive records them.
   */
  fun parseOrNull(input: SeekableSource, from: Long = 0, to: Long = input.size): ZipLayout? {
    // Without zip64 an archive records 32-bit offsets, and the layout holds them as Int.
    if (to - from > Int.MAX_VALUE) return null
    val size = (to - from).toInt()
    if (size < EOCD_SIZE) return null
    val tailStart = maxOf(0, size - EOCD_SIZE - MAX_COMMENT_SIZE)
    val tail = input.readBytes(from + tailStart, size - tailStart)
    val eocd = findEocd(tail, tailStart, size) ?: return null
    val entryCount = u16(tail, eocd - tailStart + 10)
    val directorySize = u32(tail, eocd - tailStart + 12)
    val directoryOffset = u32(tail, eocd - tailStart + 16)
    if (entryCount == ZIP64_MARKER_16 ||
      directorySize == ZIP64_MARKER_32 ||
      directoryOffset == ZIP64_MARKER_32
    ) {
      return null // zip64, which this reader deliberately does not claim to understand
    }
    if (directoryOffset > Int.MAX_VALUE || directorySize > Int.MAX_VALUE) return null
    val directoryStart = directoryOffset.toInt()
    if (directoryStart + directorySize > eocd) return null
    val directory = input.readBytes(from + directoryStart, directorySize.toInt())

    val entries = ArrayList<ZipEntry>(entryCount)
    var cursor = 0
    repeat(entryCount) {
      val entry = readCentralEntry(directory, cursor, Local(input, from, eocd)) ?: return null
      entries.add(entry.first)
      cursor = entry.second
    }
    if (cursor != directory.size) return null

    entries.sortBy { it.localHeaderOffset }
    val regions = buildRegions(entries, directoryStart, size) ?: return null
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

  /** Where local records are read from: the archive, up to its end of central directory. */
  private class Local(val input: SeekableSource, val from: Long, val limit: Int)

  /**
   * Returns the entry whose central header is at [at] in [directory], and the offset of the next
   * header there.
   */
  private fun readCentralEntry(directory: ByteArray, at: Int, local: Local): Pair<ZipEntry, Int>? {
    // A header running past the directory is refused here rather than read from what follows it:
    // the directory would then not end where the archive says, which refuses it all the same.
    if (CENTRAL_HEADER_SIZE > directory.size - at) return null
    if (u32(directory, at) != CENTRAL_SIGNATURE.toLong()) return null
    val flags = u16(directory, at + 8)
    val method = u16(directory, at + 10)
    val crc32 = u32(directory, at + 16).toUInt()
    val compressedSize = u32(directory, at + 20)
    val uncompressedSize = u32(directory, at + 24)
    val nameLength = u16(directory, at + 28)
    val extraLength = u16(directory, at + 30)
    val commentLength = u16(directory, at + 32)
    val localOffset = u32(directory, at + 42)
    val next = at.toLong() + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
    if (next > directory.size) return null
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

    val name = directory.decodeToString(
      at + CENTRAL_HEADER_SIZE,
      at + CENTRAL_HEADER_SIZE + nameLength
    )
    val limit = local.limit
    val localHeaderOffset = localOffset.toInt()
    if (localHeaderOffset > limit - LOCAL_HEADER_SIZE) return null
    val header = local.input.readBytes(local.from + localHeaderOffset, LOCAL_HEADER_SIZE)
    if (u32(header, 0) != LOCAL_SIGNATURE.toLong()) return null
    val localNameLength = u16(header, 26)
    val localExtraLength = u16(header, 28)
    val dataOffset =
      localHeaderOffset.toLong() + LOCAL_HEADER_SIZE + localNameLength + localExtraLength
    val dataEnd = dataOffset + compressedSize
    if (dataEnd > limit) return null

    var recordEnd = dataEnd.toInt()
    if (flags and FLAG_DATA_DESCRIPTOR != 0) {
      val descriptorSize = when {
        16 <= limit - recordEnd && hasDescriptorSignature(local, recordEnd) -> 16
        12 <= limit - recordEnd -> 12
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
      dataOffset = dataOffset.toInt(),
      recordEnd = recordEnd
    )
    return entry to next.toInt()
  }

  private fun hasDescriptorSignature(local: Local, at: Int): Boolean =
    u32(local.input.readBytes(local.from + at, 4), 0) == DESCRIPTOR_SIGNATURE.toLong()

  /**
   * The archive offset of the end-of-central-directory record, searched for in [tail]: the last
   * bytes of an archive of [size] bytes, from [tailStart] on.
   */
  private fun findEocd(tail: ByteArray, tailStart: Int, size: Int): Int? {
    for (at in size - EOCD_SIZE downTo tailStart) {
      if (u32(tail, at - tailStart) != EOCD_SIGNATURE.toLong()) continue
      val commentLength = u16(tail, at - tailStart + 20)
      if (at + EOCD_SIZE + commentLength == size) return at
    }
    return null
  }

  private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

  private fun u32(bytes: ByteArray, at: Int): Long =
    (u16(bytes, at).toLong()) or (u16(bytes, at + 2).toLong() shl 16)
}
