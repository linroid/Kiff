package com.linroid.kiff.format

import com.linroid.kiff.KiffException

/**
 * How one region of the target is encoded.
 *
 * This is what lets regions nest and lets a leaf choose its own encoding: the reader dispatches on
 * the code rather than assuming every region speaks the delta instruction set. Codes are part of
 * the patch format and never change meaning.
 */
enum class RegionEncoding(internal val code: Int) {
  /** Children tile this region's target range, in order. */
  COMPOSITE(0),

  /** The delta instruction set: ADD, COPY, RUN, DIFF. */
  DELTA(1),

  /** The region's bytes verbatim, taken from the literal stream. */
  RAW(2),

  /** A line-level edit script, for regions that are text. */
  TEXT(3),

  /** A table of fixed-width rows, rearranged into columns of differences before being described. */
  COLUMNS(4);

  internal companion object {
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

  /**
   * A region described in a rearranged form: both sides are transformed the same way, [inner]
   * describes the target's transformed bytes against the source's, and the result is transformed
   * back. The rearrangement is exact, so this costs nothing but the chance that it helps.
   */
  class Columns(
    override val targetLength: Long,
    val sourceFrom: Long,
    val sourceLength: Long,
    val widths: List<Int>,
    val inner: RegionNode
  ) : RegionNode()
}

/** How this node describes its bytes. */
internal fun RegionNode.encoding(): RegionEncoding = when (this) {
  is RegionNode.Composite -> RegionEncoding.COMPOSITE
  is RegionNode.Delta -> RegionEncoding.DELTA
  is RegionNode.Text -> RegionEncoding.TEXT
  is RegionNode.Columns -> RegionEncoding.COLUMNS
  is RegionNode.Raw -> RegionEncoding.RAW
}

/**
 * Walks the target alongside the tree, so each region can be checksummed against the bytes it is
 * supposed to produce. Null when the patch carries no region checksums.
 */
internal class TargetWalk(val bytes: ByteArray, var at: Int = 0)

/** Serializes a region tree. Depth-first, which is the order the literal stream is consumed in. */
internal fun writeRegionTree(out: ByteWriter, node: RegionNode, walk: TargetWalk? = null) {
  out.writeVarLong(node.targetLength)
  when (node) {
    is RegionNode.Composite -> {
      out.writeByte(RegionEncoding.COMPOSITE.code)
      out.writeVarInt(node.children.size)
      // A composite produces nothing of its own; its children cover every byte of it.
      for (child in node.children) writeRegionTree(out, child, walk)
    }
    is RegionNode.Delta -> {
      out.writeByte(RegionEncoding.DELTA.code)
      writeRegionChecksum(out, walk, node.targetLength)
      out.writeVarInt(node.instructions.size)
      out.writeBytes(node.instructions)
    }
    is RegionNode.Raw -> {
      out.writeByte(RegionEncoding.RAW.code)
      writeRegionChecksum(out, walk, node.targetLength)
    }
    is RegionNode.Text -> {
      out.writeByte(RegionEncoding.TEXT.code)
      writeRegionChecksum(out, walk, node.targetLength)
      out.writeVarLong(node.sourceFrom)
      out.writeVarLong(node.sourceLength)
      out.writeVarInt(node.edits.size)
      out.writeBytes(node.edits)
    }
    is RegionNode.Columns -> {
      out.writeByte(RegionEncoding.COLUMNS.code)
      writeRegionChecksum(out, walk, node.targetLength)
      out.writeVarLong(node.sourceFrom)
      out.writeVarLong(node.sourceLength)
      out.writeByte(node.widths.size)
      for (width in node.widths) out.writeByte(width)
      // The region inside works on rearranged bytes, which are not target bytes, so it carries no
      // checksum of its own - this node's covers what it finally produces.
      writeRegionTree(out, node.inner, null)
    }
  }
}

private fun writeRegionChecksum(out: ByteWriter, walk: TargetWalk?, length: Long) {
  if (walk == null) return
  val to = walk.at + length.toInt()
  out.writeUInt32(Crc32.compute(walk.bytes, walk.at, to))
  walk.at = to
}

/**
 * Bytes this node and its children spend on structure, as opposed to content.
 *
 * This counts what [writeRegionTree] will actually write, its own framing included: a node costs a
 * length and an encoding byte before it describes anything. Counting only the instructions would
 * make every node look free to add, which would bias each weigh-in in favour of taking a region
 * apart - by exactly the framing the comparison forgot.
 */
internal fun RegionNode.structureBytes(checksummed: Boolean = true): Long {
  // Every region that produces target bytes carries a checksum of them, and a region that is only
  // considered has to be priced with one, or taking a region apart looks cheaper than it is.
  val checksum = if (checksummed) CHECKSUM_BYTES else 0
  return when (this) {
    is RegionNode.Composite ->
      varSize(targetLength) + 1 + varSize(children.size.toLong()) +
        children.sumOf { it.structureBytes(checksummed) }
    is RegionNode.Delta ->
      varSize(targetLength) + 1 + checksum +
        varSize(instructions.size.toLong()) + instructions.size
    is RegionNode.Text ->
      varSize(targetLength) + 1 + checksum + varSize(sourceFrom) + varSize(sourceLength) +
        varSize(edits.size.toLong()) + edits.size
    is RegionNode.Columns ->
      varSize(targetLength) + 1 + checksum + varSize(sourceFrom) + varSize(sourceLength) +
        1 + widths.size + inner.structureBytes(checksummed = false)
    is RegionNode.Raw -> varSize(targetLength) + 1 + checksum
  }
}

/** A CRC-32 per region that produces target bytes. */
private const val CHECKSUM_BYTES = 4L

/** Bytes a varint of [value] occupies: seven bits at a time. */
private fun varSize(value: Long): Long {
  var remaining = value
  var bytes = 1L
  while (remaining >= 0x80) {
    remaining = remaining ushr 7
    bytes++
  }
  return bytes
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
