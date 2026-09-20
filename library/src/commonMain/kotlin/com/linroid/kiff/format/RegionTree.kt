package com.linroid.kiff.format

import com.linroid.kiff.KiffException

/**
 * How one region of the target is encoded.
 *
 * This is what lets regions nest and lets a leaf choose its own encoding: the reader dispatches on
 * the code rather than assuming every region speaks the delta instruction set. Codes are part of
 * the patch format and never change meaning.
 */
internal enum class RegionEncoding(val code: Int) {
  /** Children tile this region's target range, in order. */
  COMPOSITE(0),

  /** The delta instruction set: ADD, COPY, RUN, DIFF. */
  DELTA(1),

  /** The region's bytes verbatim, taken from the literal stream. */
  RAW(2),

  /** A line-level edit script, for regions that are text. */
  TEXT(3);

  companion object {
    fun fromCode(code: Int): RegionEncoding = entries.firstOrNull { it.code == code }
      ?: throw KiffException.InvalidPatch("Unknown region encoding $code")
  }
}

/**
 * One node of the region tree a patch is built from.
 *
 * A zip is a [Composite] of its entries, gaps and directory; a dex entry inside it can be a
 * [Composite] of its sections; a leaf is whatever encoding suits its bytes. Nesting is why the tree
 * is a tree at all - the alternative, a flat list, cannot say that these sections belong to that
 * entry, so it cannot let an entry's encoder work on the entry alone.
 *
 * Literals are deliberately *not* here. Every leaf that carries content appends it to one stream
 * shared by the whole patch, consumed in the same depth-first order the tree is written in, so the
 * content of a patch stays a single block that compresses as one. Giving each leaf its own stream
 * would cost far more than the structure saves.
 */
internal sealed class RegionNode {

  /** Bytes of the target this node covers. */
  abstract val targetLength: Long

  class Composite(val children: List<RegionNode>) : RegionNode() {
    override val targetLength: Long = children.sumOf { it.targetLength }
  }

  class Delta(
    override val targetLength: Long,
    val instructions: ByteArray
  ) : RegionNode()

  class Raw(override val targetLength: Long) : RegionNode()

  /**
   * [sourceFrom] and [sourceLength] are not redundant with the delta encoding's absolute offsets:
   * a text edit script copies source *lines*, so it has to say which source range those lines were
   * split out of.
   */
  class Text(
    override val targetLength: Long,
    val sourceFrom: Long,
    val sourceLength: Long,
    val edits: ByteArray
  ) : RegionNode()
}

/** Serializes a region tree. Depth-first, which is the order the literal stream is consumed in. */
internal fun writeRegionTree(out: ByteWriter, node: RegionNode) {
  out.writeVarLong(node.targetLength)
  when (node) {
    is RegionNode.Composite -> {
      out.writeByte(RegionEncoding.COMPOSITE.code)
      out.writeVarInt(node.children.size)
      for (child in node.children) writeRegionTree(out, child)
    }
    is RegionNode.Delta -> {
      out.writeByte(RegionEncoding.DELTA.code)
      out.writeVarInt(node.instructions.size)
      out.writeBytes(node.instructions)
    }
    is RegionNode.Raw -> out.writeByte(RegionEncoding.RAW.code)
    is RegionNode.Text -> {
      out.writeByte(RegionEncoding.TEXT.code)
      out.writeVarLong(node.sourceFrom)
      out.writeVarLong(node.sourceLength)
      out.writeVarInt(node.edits.size)
      out.writeBytes(node.edits)
    }
  }
}

/** Bytes this node and its children spend on structure, as opposed to content. */
internal fun RegionNode.structureBytes(): Long = when (this) {
  is RegionNode.Composite -> children.sumOf { it.structureBytes() }
  is RegionNode.Delta -> instructions.size.toLong()
  is RegionNode.Text -> edits.size.toLong()
  is RegionNode.Raw -> 0L
}

/**
 * Whether an encoding is worth keeping over storing the region's bytes outright.
 *
 * The cheap comparison is on unpacked sizes, and for most regions it settles the question. It is
 * wrong in exactly one important case: a difference-encoded region carries one byte per target byte
 * and so never looks smaller unpacked, yet those bytes are mostly zero and pack to almost nothing.
 * Deciding there on the unpacked size would discard the encoding that wins most, so when the cheap
 * comparison says no, the question is re-asked about what actually reaches the patch.
 */
internal fun beatsStoring(
  structure: Long,
  candidate: ByteArray,
  target: ByteArray,
  from: Int,
  to: Int
): Boolean {
  if (structure + candidate.size <= to - from) return true
  val raw = target.copyOfRange(from, to)
  return structure + Lzss.compress(candidate).size < Lzss.compress(raw).size
}

/**
 * How deep a patch may nest.
 *
 * Archive inside archive inside format leaves room for more than Kiff itself builds, and the cap is
 * what stops a malformed patch from recursing the reader off its stack.
 */
internal const val MAX_REGION_DEPTH = 16
