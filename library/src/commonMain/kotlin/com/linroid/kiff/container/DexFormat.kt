package com.linroid.kiff.container

import com.linroid.kiff.region.RegionKind

/**
 * Dalvik executables, taken apart into the sections their own map list declares.
 *
 * A dex is mostly tables of offsets into itself. Add one method and every `method_id` after it
 * shifts, so a byte search finds nothing to match and the difference encoding cannot help either -
 * it absorbs a *uniform* shift, and these shifts differ from table to table. On a real pair of
 * builds that leaves a dex patch costing about 90% of the dex itself.
 *
 * Splitting by section is the cheap half of the answer. It does not fix the renumbering, but it
 * stops each table being searched against the whole file and lets it be compared against the table
 * it actually corresponds to, where the shift is at least locally consistent.
 *
 * The map list makes this nearly free to implement: the format requires it to be sorted by offset
 * and to cover the file, so consecutive offsets are exactly the section boundaries. No item sizes
 * have to be understood, which is what keeps this a description of the layout rather than a second
 * implementation of the format.
 */
class DexFormat : ContainerFormat {

  override val name: String get() = "dex"

  /** `dex\n`, three version digits, and a NUL. */
  override fun detect(bytes: ByteArray, from: Int, to: Int): Boolean {
    if (to - from < HEADER_SIZE) return false
    for (i in MAGIC.indices) if (bytes[from + i] != MAGIC[i]) return false
    for (i in 4 until 7) {
      val digit = bytes[from + i].toInt()
      if (digit < '0'.code || digit > '9'.code) return false
    }
    return bytes[from + 7] == 0.toByte()
  }

  override fun decompose(bytes: ByteArray, from: Int, to: Int): List<Child> {
    if (!detect(bytes, from, to)) return emptyList()
    // Only little-endian dex files are described here; a byte-swapped one is rare enough that
    // getting it wrong quietly would be worse than leaving it whole.
    if (u32(bytes, from + ENDIAN_TAG) != LITTLE_ENDIAN) return emptyList()

    val mapOffset = u32(bytes, from + MAP_OFF)
    if (mapOffset < HEADER_SIZE || from + mapOffset + 4 > to) return emptyList()
    val count = u32(bytes, from + mapOffset)
    if (count <= 0 || count > MAX_SECTIONS) return emptyList()
    if (from + mapOffset + 4 + count * MAP_ITEM_SIZE > to) return emptyList()

    val offsets = IntArray(count)
    val types = IntArray(count)
    var previous = -1
    for (i in 0 until count) {
      val at = from + mapOffset + 4 + i * MAP_ITEM_SIZE
      types[i] = u16(bytes, at)
      // map_item is: u16 type, u16 unused, u32 size, u32 offset - the offset is the last field.
      val offset = u32(bytes, at + MAP_ITEM_OFFSET)
      // The format requires the list sorted by offset and inside the file; anything else is not a
      // dex this can describe, and describing it wrongly would produce a patch that cannot restore.
      if (offset <= previous || from + offset > to) return emptyList()
      offsets[i] = offset
      previous = offset
    }
    if (offsets[0] != 0) return emptyList()

    val children = ArrayList<Child>(count)
    for (i in 0 until count) {
      // A section runs to wherever the next one starts, which absorbs any alignment padding after
      // it. The last runs to the end of the region, so trailing bytes belong to something too.
      val end = if (i + 1 < count) from + offsets[i + 1] else to
      children.add(
        Child(
          name = nameOf(types[i]),
          kind = kindOf(types[i]),
          from = from + offsets[i],
          to = end
        )
      )
    }
    return children
  }

  private fun nameOf(type: Int): String = SECTION_NAMES[type] ?: "section 0x${type.toString(16)}"

  /**
   * Tables of ids and offsets are [RegionKind.INDEX]; the things they point at are
   * [RegionKind.CONTENT]. It is the index tables that renumber wholesale between builds, so telling
   * them apart is what makes an attribution of a dex patch readable.
   */
  private fun kindOf(type: Int): RegionKind = when (type) {
    TYPE_CODE, TYPE_STRING_DATA, TYPE_CLASS_DATA, TYPE_DEBUG_INFO,
    TYPE_ANNOTATIONS, TYPE_ENCODED_ARRAYS -> RegionKind.CONTENT
    else -> RegionKind.INDEX
  }

  private fun u16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

  private fun u32(bytes: ByteArray, at: Int): Int {
    var value = 0
    for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
    return value
  }

  private companion object {
    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

    const val HEADER_SIZE = 112
    const val ENDIAN_TAG = 0x28
    const val MAP_OFF = 0x34
    const val LITTLE_ENDIAN = 0x12345678
    const val MAP_ITEM_SIZE = 12
    const val MAP_ITEM_OFFSET = 8
    const val MAX_SECTIONS = 4096

    const val TYPE_CODE = 0x2001
    const val TYPE_STRING_DATA = 0x2002
    const val TYPE_DEBUG_INFO = 0x2003
    const val TYPE_ANNOTATIONS = 0x2004
    const val TYPE_ENCODED_ARRAYS = 0x2005
    const val TYPE_CLASS_DATA = 0x2000

    val SECTION_NAMES = mapOf(
      0x0000 to "header",
      0x0001 to "string_ids",
      0x0002 to "type_ids",
      0x0003 to "proto_ids",
      0x0004 to "field_ids",
      0x0005 to "method_ids",
      0x0006 to "class_defs",
      0x0007 to "call_site_ids",
      0x0008 to "method_handles",
      0x1000 to "map_list",
      0x1001 to "type_list",
      0x1002 to "annotation_set_refs",
      0x1003 to "annotation_sets",
      TYPE_CLASS_DATA to "class_data",
      TYPE_CODE to "code",
      TYPE_STRING_DATA to "string_data",
      TYPE_DEBUG_INFO to "debug_info",
      TYPE_ANNOTATIONS to "annotations",
      TYPE_ENCODED_ARRAYS to "encoded_arrays",
      0x2006 to "annotations_dir"
    )
  }
}
