package com.linroid.kiff.format

/**
 * [patch] with its region tree packed, whether or not that makes it smaller: what a writer is
 * entitled to write, and a way to reach a packed tree from inputs small enough to corrupt byte by
 * byte.
 */
internal fun withPackedTree(patch: ByteArray): ByteArray = rewriteTree(patch, packed = true)

/** [patch] with its region tree as it is, which is what Kiff 0.1 and 0.2 wrote. */
internal fun withStoredTree(patch: ByteArray): ByteArray = rewriteTree(patch, packed = false)

private fun rewriteTree(patch: ByteArray, packed: Boolean): ByteArray {
  val reader = ByteReader(patch)
  val header = PatchFormat.readHeader(reader)
  val headerEnd = reader.offset
  val treeLength = reader.readVarInt()
  val storedTreeLength = if (header.treePacked) reader.readVarInt() else treeLength
  val contentLength = reader.readVarInt()
  val encoding = reader.readByte()
  val storedContentLength = reader.readVarInt()
  val storedTree = reader.readBytes(storedTreeLength)
  val tree = if (header.treePacked) LzHuffman.decompress(storedTree, treeLength) else storedTree
  val content = reader.readBytes(reader.remaining)

  val out = ByteWriter(patch.size + 64)
  out.writeBytes(patch, 0, headerEnd)
  out.writeVarInt(tree.size)
  val written = if (packed) LzHuffman.compress(tree) else tree
  if (packed) out.writeVarInt(written.size)
  out.writeVarInt(contentLength)
  out.writeByte(encoding)
  out.writeVarInt(storedContentLength)
  out.writeBytes(written)
  out.writeBytes(content)
  val flags = if (packed) {
    header.flags or PatchFormat.FLAG_PACKED_TREE
  } else {
    header.flags and PatchFormat.FLAG_PACKED_TREE.inv()
  }
  return out.toByteArray().also { it[FLAGS_AT] = flags.toByte() }
}

/** After the magic, the version and the patcher. */
private const val FLAGS_AT = 6
