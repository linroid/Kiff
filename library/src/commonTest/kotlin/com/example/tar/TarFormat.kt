package com.example.tar

import com.linroid.kiff.container.Child
import com.linroid.kiff.container.ContainerFormat
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readBytes
import com.linroid.kiff.region.RegionKind

/**
 * Tar archives, taken apart into their members: a format Kiff does not ship, added the way anyone
 * outside Kiff would add one.
 *
 * It is written against Kiff's public API alone, which is why it lives in a package of its own,
 * and it is meant to be read as an example. It reads each member's 512-byte header and skips the
 * data, so describing an archive of any size reads a few kilobytes of it; a member's size can run
 * past 8 GiB, in the base-256 form GNU tar writes for one.
 *
 * Every byte is a child. A member is its header, its data and the zeros that pad it to a block,
 * with the data as its payload, so the header and the padding are compared as framing and the data
 * gets to choose its own encoding. Whatever follows the last member - the two zero blocks that end
 * an archive, the padding to a whole record, anything appended after that - is one unnamed child.
 */
class TarFormat : ContainerFormat {

  override val name: String get() = "tar"

  /** A ustar header at the start: its magic, and a checksum that adds up. */
  override fun detect(input: SeekableSource, from: Long, to: Long): Boolean {
    if (to - from < BLOCK) return false
    return isHeader(input.readBytes(from, BLOCK))
  }

  override fun decompose(input: SeekableSource, from: Long, to: Long): List<Child> {
    val children = ArrayList<Child>()
    var at = from
    while (to - at >= BLOCK) {
      val header = input.readBytes(at, BLOCK)
      if (header.all { it == ZERO }) break
      if (!isHeader(header)) return emptyList()
      val size = sizeOf(header) ?: return emptyList()
      val dataFrom = at + BLOCK
      // Checked as a difference, so a size near Long.MAX_VALUE cannot wrap past the check.
      if (size > to - dataFrom) return emptyList()
      val padded = (size + BLOCK - 1) / BLOCK * BLOCK
      val end = if (padded > to - dataFrom) to else dataFrom + padded
      children.add(
        Child(
          name = nameOf(header),
          kind = if (header[TYPE] in METADATA) RegionKind.INDEX else RegionKind.CONTENT,
          from = at,
          to = end,
          contentFrom = dataFrom,
          contentTo = dataFrom + size
        )
      )
      at = end
    }
    if (children.isEmpty()) return emptyList()
    if (at < to) children.add(Child(END_OF_ARCHIVE, RegionKind.GAP, at, to))
    return children
  }

  private fun isHeader(header: ByteArray): Boolean {
    for (i in MAGIC.indices) if (header[MAGIC_AT + i] != MAGIC[i]) return false
    val recorded = octal(header, CHECKSUM_AT, CHECKSUM_LENGTH) ?: return false
    // The checksum is taken with its own field read as spaces.
    var sum = 0L
    for (i in header.indices) {
      sum += if (i in CHECKSUM_AT until CHECKSUM_AT + CHECKSUM_LENGTH) SPACE.toLong()
      else header[i].toLong() and 0xFF
    }
    return sum == recorded
  }

  /** The bytes that follow the header, which only some types of member have. */
  private fun sizeOf(header: ByteArray): Long? {
    if (header[TYPE] in WITHOUT_DATA) return 0
    val first = header[SIZE_AT].toInt() and 0xFF
    if (first and 0x80 == 0) return octal(header, SIZE_AT, SIZE_LENGTH)
    // GNU's base-256: the rest of the field as a big-endian number, for sizes octal cannot hold.
    if (first != 0x80) return null
    var size = 0L
    for (i in SIZE_AT + 1 until SIZE_AT + SIZE_LENGTH) {
      if (size ushr 55 != 0L) return null
      size = (size shl 8) or (header[i].toLong() and 0xFF)
    }
    return size
  }

  private fun nameOf(header: ByteArray): String {
    val name = text(header, NAME_AT, NAME_LENGTH)
    val prefix = text(header, PREFIX_AT, PREFIX_LENGTH)
    return if (prefix.isEmpty()) name else "$prefix/$name"
  }

  private fun text(header: ByteArray, at: Int, length: Int): String {
    var end = at
    while (end < at + length && header[end] != ZERO) end++
    return header.decodeToString(at, end)
  }

  /** An octal field: digits, padded with spaces or NULs on either side. */
  private fun octal(header: ByteArray, at: Int, length: Int): Long? {
    var i = at
    val end = at + length
    while (i < end && header[i] == SPACE) i++
    var value = 0L
    var digits = 0
    while (i < end && header[i].toInt() in '0'.code..'7'.code) {
      value = value * 8 + (header[i].toInt() - '0'.code)
      digits++
      i++
    }
    while (i < end && (header[i] == SPACE || header[i] == ZERO)) i++
    return if (digits > 0 && i == end) value else null
  }

  companion object {
    /** What follows the last member. */
    const val END_OF_ARCHIVE = "(end of archive)"

    const val BLOCK = 512
    private const val NAME_AT = 0
    private const val NAME_LENGTH = 100
    private const val SIZE_AT = 124
    private const val SIZE_LENGTH = 12
    private const val CHECKSUM_AT = 148
    private const val CHECKSUM_LENGTH = 8
    private const val TYPE = 156
    private const val MAGIC_AT = 257
    private const val PREFIX_AT = 345
    private const val PREFIX_LENGTH = 155

    private const val ZERO: Byte = 0
    private const val SPACE: Byte = 0x20

    /** POSIX's `ustar\0` and GNU's `ustar ` share these five bytes. */
    private val MAGIC = "ustar".encodeToByteArray()

    /** Links, devices, directories and FIFOs: their size says nothing about what follows. */
    private val WITHOUT_DATA = "123456".encodeToByteArray().toSet()

    /** pax and GNU headers that describe the member after them rather than being one. */
    private val METADATA = "xgLK".encodeToByteArray().toSet()
  }
}
