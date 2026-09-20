package com.linroid.kiff.container

/** Builds files shaped like a dex: enough header and map list for [DexFormat] to describe them. */
class TestDexBuilder {

  private val sections = mutableListOf<Pair<Int, ByteArray>>()

  /** Appends a section of the given map type. Order is the order they will be laid out in. */
  fun section(type: Int, bytes: ByteArray) = apply { sections.add(type to bytes) }

  fun build(version: String = "039"): ByteArray {
    val mapEntries = sections.size + 2 // header and map_list are sections too
    var cursor = HEADER_SIZE
    val offsets = IntArray(sections.size)
    sections.forEachIndexed { i, (_, bytes) ->
      offsets[i] = cursor
      cursor += bytes.size
    }
    val mapOffset = cursor
    val size = mapOffset + 4 + mapEntries * 12

    val out = ByteArray(size)
    "dex\n".encodeToByteArray().copyInto(out, 0)
    version.encodeToByteArray().copyInto(out, 4)
    out[7] = 0
    writeU32(out, 0x20, size)          // file_size
    writeU32(out, 0x24, HEADER_SIZE)   // header_size
    writeU32(out, 0x28, 0x12345678)    // endian_tag
    writeU32(out, 0x34, mapOffset)     // map_off

    sections.forEachIndexed { i, (_, bytes) -> bytes.copyInto(out, offsets[i]) }

    writeU32(out, mapOffset, mapEntries)
    var at = mapOffset + 4
    at = writeMapItem(out, at, 0x0000, 1, 0)
    sections.forEachIndexed { i, (type, bytes) ->
      at = writeMapItem(out, at, type, bytes.size, offsets[i])
    }
    writeMapItem(out, at, 0x1000, 1, mapOffset)
    return out
  }

  private fun writeMapItem(out: ByteArray, at: Int, type: Int, size: Int, offset: Int): Int {
    out[at] = (type and 0xFF).toByte()
    out[at + 1] = ((type shr 8) and 0xFF).toByte()
    writeU32(out, at + 4, size)
    writeU32(out, at + 8, offset)
    return at + 12
  }

  private fun writeU32(out: ByteArray, at: Int, value: Int) {
    for (i in 0 until 4) out[at + i] = ((value ushr (8 * i)) and 0xFF).toByte()
  }

  private companion object {
    const val HEADER_SIZE = 112
  }
}
