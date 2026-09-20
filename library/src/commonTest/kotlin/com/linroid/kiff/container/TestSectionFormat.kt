package com.linroid.kiff.container

import com.linroid.kiff.region.RegionKind

/**
 * A toy container: a magic, a count, a length per section, then the sections.
 *
 * It stands in for the formats Kiff does not understand yet - a dex and its sections above all.
 * The point of having it in the tests is to prove the claim the seam is for: a format Kiff had
 * never heard of becomes a class, and the patch format, the node encodings and the reader all stay
 * exactly where they were.
 */
class TestSectionFormat : ContainerFormat {

  override val name: String get() = "sect"

  override fun detect(bytes: ByteArray, from: Int, to: Int): Boolean {
    if (to - from < HEADER) return false
    for (i in MAGIC.indices) if (bytes[from + i] != MAGIC[i]) return false
    return true
  }

  override fun decompose(bytes: ByteArray, from: Int, to: Int): List<Child> {
    if (!detect(bytes, from, to)) return emptyList()
    val count = u32(bytes, from + MAGIC.size)
    if (count < 0 || count > MAX_SECTIONS) return emptyList()
    val header = MAGIC.size + 4 + count * 4
    if (from + header > to) return emptyList()

    val children = ArrayList<Child>(count + 1)
    // The header is a child like any other: every byte must belong to something.
    children.add(
      Child(name = "(section table)", kind = RegionKind.INDEX, from = from, to = from + header)
    )
    var cursor = from + header
    for (i in 0 until count) {
      val length = u32(bytes, from + MAGIC.size + 4 + i * 4)
      if (length < 0 || cursor + length > to) return emptyList()
      children.add(
        Child(name = "section $i", kind = RegionKind.CONTENT, from = cursor, to = cursor + length)
      )
      cursor += length
    }
    // Anything after the last section still has to be named, or the file cannot be rebuilt.
    if (cursor < to) {
      children.add(Child(name = "(trailer)", kind = RegionKind.GAP, from = cursor, to = to))
    }
    return children
  }

  private fun u32(bytes: ByteArray, at: Int): Int {
    var value = 0
    for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
    return value
  }

  companion object {
    private val MAGIC = "SECT".encodeToByteArray()
    private const val HEADER = 8
    private const val MAX_SECTIONS = 1024

    /** Builds a file this format understands. */
    fun build(sections: List<ByteArray>, trailer: ByteArray = ByteArray(0)): ByteArray {
      val header = MAGIC.size + 4 + sections.size * 4
      val out = ByteArray(header + sections.sumOf { it.size } + trailer.size)
      MAGIC.copyInto(out, 0)
      writeU32(out, MAGIC.size, sections.size)
      sections.forEachIndexed { i, s -> writeU32(out, MAGIC.size + 4 + i * 4, s.size) }
      var cursor = header
      for (section in sections) {
        section.copyInto(out, cursor)
        cursor += section.size
      }
      trailer.copyInto(out, cursor)
      return out
    }

    private fun writeU32(bytes: ByteArray, at: Int, value: Int) {
      for (i in 0 until 4) bytes[at + i] = ((value ushr ((3 - i) * 8)) and 0xFF).toByte()
    }
  }
}
