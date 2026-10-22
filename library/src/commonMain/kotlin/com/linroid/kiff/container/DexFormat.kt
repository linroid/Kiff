package com.linroid.kiff.container

import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readBytes
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
public class DexFormat : ContainerFormat {

  override val name: String get() = "dex"

  /** `dex\n`, three version digits, and a NUL. */
  override fun detect(input: SeekableSource, from: Long, to: Long): Boolean {
    if (to - from < HEADER_SIZE) return false
    return hasMagic(input.readBytes(from, MAGIC_SIZE))
  }

  /** Reads the header and the map list, and nothing the map list points at. */
  override fun decompose(input: SeekableSource, from: Long, to: Long): List<Child> {
    if (to - from < HEADER_SIZE) return emptyList()
    val header = input.readBytes(from, HEADER_SIZE)
    if (!hasMagic(header)) return emptyList()
    // Only little-endian dex files are described here; a byte-swapped one is rare enough that
    // getting it wrong quietly would be worse than leaving it whole.
    if (u32(header, ENDIAN_TAG) != LITTLE_ENDIAN) return emptyList()

    // Offsets are the file's own, counted from its first byte, so each is checked against the
    // file's size before it is added to [from] and read at.
    val size = to - from
    val mapOffset = u32(header, MAP_OFF)
    if (mapOffset < HEADER_SIZE || mapOffset > size - 4) return emptyList()
    val count = u32(input.readBytes(from + mapOffset, 4), 0)
    if (count <= 0 || count > MAX_SECTIONS) return emptyList()
    if (count * MAP_ITEM_SIZE > size - mapOffset - 4) return emptyList()
    val map = input.readBytes(from + mapOffset + 4, count.toInt() * MAP_ITEM_SIZE)

    val offsets = LongArray(count.toInt())
    val types = IntArray(count.toInt())
    var previous = -1L
    for (i in offsets.indices) {
      val at = i * MAP_ITEM_SIZE
      types[i] = u16(map, at)
      // map_item is: u16 type, u16 unused, u32 size, u32 offset - the offset is the last field.
      val offset = u32(map, at + MAP_ITEM_OFFSET)
      // The format requires the list sorted by offset and inside the file; anything else is not a
      // dex this can describe, and describing it wrongly would produce a patch that cannot restore.
      if (offset <= previous || offset > size) return emptyList()
      offsets[i] = offset
      previous = offset
    }
    if (offsets[0] != 0L) return emptyList()

    val children = ArrayList<Child>(offsets.size)
    for (i in offsets.indices) {
      // A section runs to wherever the next one starts, which absorbs any alignment padding after
      // it. The last runs to the end of the region, so trailing bytes belong to something too.
      val end = if (i + 1 < offsets.size) from + offsets[i + 1] else to
      children.add(
        Child(
          name = nameOf(types[i]),
          kind = kindOf(types[i]),
          from = from + offsets[i],
          to = end,
          columns = columnsOf(types[i])
        )
      )
    }
    return children
  }

  private fun hasMagic(header: ByteArray): Boolean {
    for (i in MAGIC.indices) if (header[i] != MAGIC[i]) return false
    for (i in 4 until 7) {
      val digit = header[i].toInt()
      if (digit < '0'.code || digit > '9'.code) return false
    }
    return header[7] == 0.toByte()
  }

  /**
   * The row layout of the sections that are tables, from the dex specification.
   *
   * These are the sections a byte search cannot touch: every entry holds an index or an offset
   * into the rest of the file, so adding anything shifts all of them and no run survives. Read as
   * columns of differences they become mostly stable.
   */
  private fun columnsOf(type: Int): List<Int>? = when (type) {
    TYPE_STRING_IDS -> listOf(4)                  // string_data_off
    TYPE_TYPE_IDS -> listOf(4)                    // descriptor_idx
    TYPE_PROTO_IDS -> listOf(4, 4, 4)             // shorty_idx, return_type_idx, parameters_off
    TYPE_FIELD_IDS -> listOf(2, 2, 4)             // class_idx, type_idx, name_idx
    TYPE_METHOD_IDS -> listOf(2, 2, 4)            // class_idx, proto_idx, name_idx
    TYPE_CLASS_DEFS -> List(8) { 4 }              // eight u32 fields
    TYPE_CALL_SITE_IDS -> listOf(4)               // call_site_off
    TYPE_METHOD_HANDLES -> listOf(2, 2, 2, 2)     // type, unused, field_or_method_id, unused
    else -> null
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

  /** Unsigned, as the format means it: an offset past 2 GB is an offset, not a negative one. */
  private fun u32(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    return value
  }

  private companion object {
    private val MAGIC = byteArrayOf(0x64, 0x65, 0x78, 0x0A) // "dex\n"

    /** The magic, the version digits and their NUL. */
    const val MAGIC_SIZE = 8
    const val HEADER_SIZE = 112
    const val ENDIAN_TAG = 0x28
    const val MAP_OFF = 0x34
    const val LITTLE_ENDIAN = 0x12345678L
    const val MAP_ITEM_SIZE = 12
    const val MAP_ITEM_OFFSET = 8
    const val MAX_SECTIONS = 4096

    const val TYPE_STRING_IDS = 0x0001
    const val TYPE_TYPE_IDS = 0x0002
    const val TYPE_PROTO_IDS = 0x0003
    const val TYPE_FIELD_IDS = 0x0004
    const val TYPE_METHOD_IDS = 0x0005
    const val TYPE_CLASS_DEFS = 0x0006
    const val TYPE_CALL_SITE_IDS = 0x0007
    const val TYPE_METHOD_HANDLES = 0x0008
    const val TYPE_CODE = 0x2001
    const val TYPE_STRING_DATA = 0x2002
    const val TYPE_DEBUG_INFO = 0x2003
    const val TYPE_ANNOTATIONS = 0x2004
    const val TYPE_ENCODED_ARRAYS = 0x2005
    const val TYPE_CLASS_DATA = 0x2000

    val SECTION_NAMES = mapOf(
      0x0000 to "header",
      TYPE_STRING_IDS to "string_ids",
      TYPE_TYPE_IDS to "type_ids",
      TYPE_PROTO_IDS to "proto_ids",
      TYPE_FIELD_IDS to "field_ids",
      TYPE_METHOD_IDS to "method_ids",
      TYPE_CLASS_DEFS to "class_defs",
      TYPE_CALL_SITE_IDS to "call_site_ids",
      TYPE_METHOD_HANDLES to "method_handles",
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
