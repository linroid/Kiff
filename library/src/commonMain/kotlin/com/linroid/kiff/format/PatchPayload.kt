package com.linroid.kiff.format

import com.linroid.kiff.KiffException
import com.linroid.kiff.delta.DeltaReader
import com.linroid.kiff.io.toIntIndex
import com.linroid.kiff.text.TextRegion

/**
 * The part of a patch after the header: a region tree plus the one literal stream its leaves share.
 *
 * ```
 * varint tree size    varint literal size (unpacked)
 * byte   literal flag varint stored size
 * bytes  region tree
 * bytes  literals, packed when that is smaller
 * ```
 *
 * Keeping every leaf's content in a single stream is what lets the structure be a tree without
 * paying for it: the bytes a patch carries stay contiguous and compress as one block, while the
 * tree holds only shape and instructions.
 */
internal object PatchPayload {

  fun write(root: RegionNode, literals: ByteArray, target: ByteArray?): ByteArray {
    val tree = ByteWriter(256)
    writeRegionTree(tree, root, target?.let { TargetWalk(it) })
    val treeBytes = tree.toByteArray()

    val packed = if (literals.isEmpty()) literals else Lzss.compress(literals)
    val compressed = packed.size < literals.size
    val stored = if (compressed) packed else literals

    val out = ByteWriter(treeBytes.size + stored.size + 24)
    out.writeVarInt(treeBytes.size)
    out.writeVarInt(literals.size)
    out.writeByte(if (compressed) 1 else 0)
    out.writeVarInt(stored.size)
    out.writeBytes(treeBytes)
    out.writeBytes(stored)
    return out.toByteArray()
  }

  fun read(
    source: ByteArray,
    patch: ByteArray,
    from: Int,
    targetSize: Long,
    checksums: Boolean
  ): ByteArray {
    val size = targetSize.toIntIndex("Target size")
    val container = ByteReader(patch, from)
    val treeLength = container.readVarInt()
    val literalLength = container.readVarInt()
    val literalFlag = container.readByte()
    val storedLength = container.readVarInt()

    val tree = ByteReader(patch, container.offset)
    container.skip(treeLength)
    val storedLiterals = container.readBytes(storedLength)
    val literals = when (literalFlag) {
      0 -> storedLiterals
      1 -> Lzss.decompress(storedLiterals, literalLength)
      else -> throw KiffException.InvalidPatch("Unknown literal encoding $literalFlag")
    }

    val target = ByteArray(size)
    val cursor = LiteralCursor()
    val written = applyNode(tree, source, literals, cursor, target, 0, 0, checksums)
    if (written != size) {
      throw KiffException.InvalidPatch("Patch described $written of $size target bytes")
    }
    return target
  }

  /** Fills `target[at, at + node length)` and answers how many bytes that was. */
  private fun applyNode(
    tree: ByteReader,
    source: ByteArray,
    literals: ByteArray,
    cursor: LiteralCursor,
    target: ByteArray,
    at: Int,
    depth: Int,
    checksums: Boolean
  ): Int {
    if (depth > MAX_REGION_DEPTH) {
      throw KiffException.InvalidPatch("Region tree nests deeper than $MAX_REGION_DEPTH")
    }
    val length = tree.readVarLong().toIntIndex("Region length")
    if (at + length > target.size) {
      throw KiffException.InvalidPatch("Region runs past the end of the target")
    }
    val encoding = RegionEncoding.fromCode(tree.readByte())
    // A composite produces nothing itself; its children each carry their own.
    val expected =
      if (checksums && encoding != RegionEncoding.COMPOSITE) tree.readUInt32() else null
    when (encoding) {
      RegionEncoding.COMPOSITE -> {
        val children = tree.readVarInt()
        var written = 0
        repeat(children) {
          written += applyNode(
            tree, source, literals, cursor, target, at + written, depth + 1, checksums
          )
        }
        if (written != length) {
          throw KiffException.InvalidPatch(
            "Composite region covers $written bytes but declares $length"
          )
        }
      }
      RegionEncoding.DELTA -> {
        val instructionLength = tree.readVarInt()
        val instructions = ByteReader(tree.bytes, tree.offset)
        tree.skip(instructionLength)
        cursor.value += DeltaReader.apply(
          source = source,
          instructions = instructions,
          literals = literals,
          literalFrom = cursor.value,
          target = target,
          at = at,
          length = length
        )
      }
      RegionEncoding.RAW -> {
        if (cursor.value + length > literals.size) {
          throw KiffException.InvalidPatch("Raw region reads past the literal stream")
        }
        literals.copyInto(target, at, cursor.value, cursor.value + length)
        cursor.value += length
      }
      RegionEncoding.COLUMNS -> {
        val sourceFrom = tree.readVarLong().toIntIndex("Column source offset")
        val sourceLength = tree.readVarLong().toIntIndex("Column source length")
        val count = tree.readByte()
        val widths = List(count) { tree.readByte() }
        if (!ColumnTransform.suits(widths, length)) {
          throw KiffException.InvalidPatch("Column region declares a row this cannot describe")
        }
        if (sourceFrom < 0 || sourceFrom + sourceLength > source.size) {
          throw KiffException.InvalidPatch("Column region names a source range outside the source")
        }
        // Both sides are rearranged the same way; the region inside describes one against the
        // other, and the result is rearranged back.
        val rearranged = ColumnTransform.forward(source, sourceFrom, sourceFrom + sourceLength, widths)
        val scratch = ByteArray(length)
        // The region inside describes rearranged bytes, which are not target bytes, so it
        // carries no checksum; this node's covers what the rearrangement finally produces.
        val written = applyNode(tree, rearranged, literals, cursor, scratch, 0, depth + 1, false)
        if (written != length) {
          throw KiffException.InvalidPatch("Column region produced $written of $length bytes")
        }
        ColumnTransform.inverse(scratch, widths, length).copyInto(target, at)
      }
      RegionEncoding.TEXT -> {
        val sourceFrom = tree.readVarLong().toIntIndex("Text source offset")
        val sourceLength = tree.readVarLong().toIntIndex("Text source length")
        val editLength = tree.readVarInt()
        val edits = ByteReader(tree.bytes, tree.offset)
        tree.skip(editLength)
        cursor.value += TextRegion.apply(
          source = source,
          sourceFrom = sourceFrom,
          sourceLength = sourceLength,
          edits = edits,
          literals = literals,
          literalFrom = cursor.value,
          target = target,
          at = at,
          length = length
        )
      }
    }
    if (expected != null) {
      val actual = Crc32.compute(target, at, at + length)
      if (actual != expected) {
        throw KiffException.VerificationFailed(
          "Region [$at, ${at + length}) described as ${encoding.name.lowercase()} restored to " +
            "${actual.toHex()}, not the ${expected.toHex()} it was built from"
        )
      }
    }
    return length
  }

  /** Where the next leaf's content starts; leaves consume the shared stream in tree order. */
  private class LiteralCursor(var value: Int = 0)
}
