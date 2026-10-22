package com.linroid.kiff.format

import com.linroid.kiff.KiffException
import com.linroid.kiff.delta.DeltaReader
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.CheckedRestoreTarget
import com.linroid.kiff.io.CountingRestoreTarget
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.ScratchRestoreTarget
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readFully
import com.linroid.kiff.text.TextRegion

/**
 * The part of a patch after the header: a region tree plus the one literal stream its leaves share.
 *
 * ```
 * varint tree size (unpacked)   [varint tree size as stored, when the tree is packed]
 * varint literal size (unpacked)
 * byte   literal flag           varint stored size
 * bytes  region tree, packed when that makes the patch smaller
 * bytes  literals, packed when that is smaller
 * ```
 *
 * Keeping every leaf's content in a single stream is what lets the structure be a tree without
 * paying for it: the bytes a patch carries stay contiguous and compress as one block, while the
 * tree holds only shape and instructions.
 *
 * One stream rather than several is a decision that has been measured, because the obvious
 * objection to it is right about the statistics. What a patch carries has two very different
 * populations: the differences a DIFF region emits pack to 18% of themselves, being mostly zero,
 * while the literals an ADD region emits only reach 56%, and one Huffman tree has to straddle
 * both. Splitting them into streams of their own and packing each separately was worth **3.6%** of
 * a real patch - the tree fits each population better, but the matcher had already found the long
 * runs of zeroes whichever tree coded them, which is where most of that compressibility was
 * already going. Against the format change it would take - several cursors, an order to consume
 * them in, and both halves of the codec knowing about it - that is not a trade worth making.
 * [LzHuffman] records where the remaining compression actually is.
 *
 * Applying one holds neither file. The source is addressed through a [SeekableSource] and the
 * target is written out as it is produced, which is what lets a device with far less memory than
 * the machine that built the patch apply it. What is held is the patch, its content stream unpacked
 * whole, its tree unpacked whole when it was packed, and what a single region needs at once - so a
 * patch whose target is mostly new content costs about its target in memory, since that content is
 * what the stream carries.
 */
internal object PatchPayload {

  /**
   * Content encodings, as written in the payload header.
   *
   * Code 1 was LZ77 matching without the entropy coding. No released Kiff wrote it, and it is not
   * part of version 1: its stored size bounds nothing it unpacks to, so it was the one way for a
   * few bytes of patch to name an allocation as large as the target.
   */
  const val CONTENT_STORED = 0
  const val CONTENT_LZ_HUFFMAN = 2

  /** A payload, and whether its tree is packed, which the header's flags have to say. */
  class Written(val bytes: ByteArray, val treePacked: Boolean)

  /**
   * Writes [root] and its content as a payload, packing the tree only where that makes the patch
   * strictly smaller.
   *
   * Where it does not, the payload is exactly the one a writer without packed trees would have
   * written, so a patch that gains nothing from the flag never carries it, and stays readable by
   * the Kiff releases that predate it.
   */
  fun write(root: RegionNode, literals: ByteArray, target: ByteArray?): Written {
    val tree = ByteWriter(256)
    writeRegionTree(tree, root, target?.let { TargetWalk(it) })
    val treeBytes = tree.toByteArray()
    val packedTree = packTree(treeBytes, root.targetLength)

    val packed = if (literals.isEmpty()) literals else LzHuffman.compress(literals)
    val compressed = packed.size < literals.size
    val stored = if (compressed) packed else literals
    val storedTree = packedTree ?: treeBytes

    val out = ByteWriter(storedTree.size + stored.size + 32)
    out.writeVarInt(treeBytes.size)
    if (packedTree != null) out.writeVarInt(packedTree.size)
    out.writeVarInt(literals.size)
    out.writeByte(if (compressed) CONTENT_LZ_HUFFMAN else CONTENT_STORED)
    out.writeVarInt(stored.size)
    out.writeBytes(storedTree)
    out.writeBytes(stored)
    return Written(out.toByteArray(), treePacked = packedTree != null)
  }

  /**
   * [tree] packed, or null where that would not make the patch smaller: the packed form costs a
   * varint for its stored size on top of its own bytes. A tree longer than the target is never
   * packed, because a reader refuses one - see [apply].
   */
  private fun packTree(tree: ByteArray, targetSize: Long): ByteArray? {
    if (tree.size > targetSize) return null
    val packed = LzHuffman.compress(tree)
    return packed.takeIf { it.size + varSize(it.size.toLong()) < tree.size }
  }

  /**
   * Restores the target into [out], answering the checksum of everything written.
   *
   * [checksums] and [treePacked] are what the header's flags say about the payload.
   */
  fun apply(
    source: SeekableSource,
    patch: ByteArray,
    from: Int,
    targetSize: Long,
    checksums: Boolean,
    out: RestoreTarget,
    treePacked: Boolean = false
  ): UInt {
    val container = ByteReader(patch, from)
    val treeLength = container.readVarInt()
    val storedTreeLength = if (treePacked) container.readVarInt() else treeLength
    val literalLength = container.readVarInt()
    val literalFlag = container.readByte()
    val storedLength = container.readVarInt()
    // The tree is read through a window of exactly its declared size, so no region can read on into
    // the content, and anything the root leaves unread is refused once it has been applied.
    val tree = if (treePacked) {
      unpackTree(container.window(storedTreeLength), treeLength, targetSize)
    } else {
      container.window(treeLength)
    }

    // The root of the tree says how much target it describes, and that has to be the target the
    // header declared. A patch whose two sizes disagree is refused here, before anything is
    // unpacked or a byte written, rather than once its tree has run out.
    val covered = tree.copy().readVarLong()
    if (covered != targetSize) {
      throw KiffException.InvalidPatch(
        "Region tree covers $covered bytes of a $targetSize-byte target"
      )
    }

    // Unpacking is the one place a patch gets to name a size before anything has checked it, so
    // the bound is worth stating: every content byte becomes at most one target byte - an ADD, a
    // RAW region, the differences of a DIFF - so content longer than the target is a patch
    // describing more than it could ever use, and a way to ask for an arbitrary allocation.
    if (literalLength > targetSize) {
      throw KiffException.InvalidPatch(
        "Patch declares $literalLength bytes of content for a target of $targetSize"
      )
    }
    // The target size is the patch's own word too, so the stored bytes have to be able to account
    // for the size as well - or a few of them could declare a gigabyte and have it allocated.
    val possible = when (literalFlag) {
      CONTENT_STORED -> storedLength.toLong()
      CONTENT_LZ_HUFFMAN -> LzHuffman.maxUnpackedSize(storedLength)
      else -> throw KiffException.InvalidPatch("Unknown content encoding $literalFlag")
    }
    if (literalLength > possible || (literalFlag == CONTENT_STORED && literalLength < possible)) {
      throw KiffException.InvalidPatch(
        "Patch declares $literalLength bytes of content for $storedLength stored bytes"
      )
    }

    val storedLiterals = container.readBytes(storedLength)
    // The content runs to the end of the patch. Bytes after it belong to nothing, and accepting
    // them would let an appended or mis-measured patch pass for a whole one.
    if (container.remaining != 0) {
      throw KiffException.InvalidPatch("Patch has ${container.remaining} byte(s) after its content")
    }
    val literals = if (literalFlag == CONTENT_STORED) {
      storedLiterals
    } else {
      LzHuffman.decompress(storedLiterals, literalLength)
    }

    val checked = CheckedRestoreTarget(out, targetSize)
    val state = Restore(source, literals, checked, DeltaReader())
    val written = applyNode(tree, state, checked, targetSize, depth = 0, checksums = checksums)
    if (written != targetSize) {
      throw KiffException.InvalidPatch("Patch described $written of $targetSize target bytes")
    }
    // Every byte of the tree and of the content has to be accounted for: what no region used is
    // where corruption hides, and where a later version of the format would put something.
    tree.requireConsumed("Region tree")
    if (state.literalCursor != literals.size) {
      throw KiffException.InvalidPatch(
        "Patch carries ${literals.size - state.literalCursor} content byte(s) no region uses"
      )
    }
    return checked.wholeChecksum()
  }

  /**
   * The packed tree in [stored], unpacked to the [treeLength] bytes the payload declares.
   *
   * The same bounds hold as for the content, for the same reason: the unpacked size is the patch's
   * own word, and it is allocated whole. A tree is not bounded by its target the way content is -
   * a line script that deletes can run longer than what it keeps - but one that is packed has to
   * be, which keeps everything a reader allocates within the target the header declares. A writer
   * that would pack a longer tree writes it as it is instead.
   */
  private fun unpackTree(stored: ByteReader, treeLength: Int, targetSize: Long): ByteReader {
    if (treeLength > targetSize) {
      throw KiffException.InvalidPatch(
        "Patch declares a packed region tree of $treeLength bytes for a target of $targetSize"
      )
    }
    val storedLength = stored.remaining
    if (treeLength > LzHuffman.maxUnpackedSize(storedLength)) {
      throw KiffException.InvalidPatch(
        "Patch declares a region tree of $treeLength bytes for $storedLength stored bytes"
      )
    }
    val bytes = stored.readBytes(storedLength)
    return ByteReader(LzHuffman.decompress(bytes, treeLength, "Region tree"))
  }

  /**
   * Fills [out] with the next node's worth of target bytes and answers how many that was.
   *
   * [Restore.checked] is the restore as a whole, which is where a region's checksum has to be
   * taken from; [out] is where this node's bytes actually go. The two differ only inside a columns
   * region, whose contents cannot be emitted until they are whole.
   *
   * A node is measured by what reached [out], not by what its decoder says it wrote, so no one
   * decoder's arithmetic stands between a malformed patch and a region of the wrong length.
   */
  private fun applyNode(
    tree: ByteReader,
    state: Restore,
    out: CountingRestoreTarget,
    room: Long,
    depth: Int,
    checksums: Boolean
  ): Long {
    if (depth > MAX_REGION_DEPTH) {
      throw KiffException.InvalidPatch("Region tree nests deeper than $MAX_REGION_DEPTH")
    }
    val length = tree.readVarLong()
    if (length < 0 || length > room) {
      throw KiffException.InvalidPatch("Region runs past the end of the target")
    }
    val encoding = RegionEncoding.fromCode(tree.readByte())
    val onTheRestore = out === state.checked
    val expected =
      if (checksums && encoding != RegionEncoding.COMPOSITE) tree.readUInt32() else null
    val startedAt = state.checked.position
    val before = out.position
    if (expected != null && onTheRestore) state.checked.startRegion()

    when (encoding) {
      RegionEncoding.COMPOSITE -> {
        val children = tree.readVarInt()
        var written = 0L
        repeat(children) {
          written += applyNode(tree, state, out, length - written, depth + 1, checksums)
        }
        if (written != length) {
          throw KiffException.InvalidPatch(
            "Composite region covers $written bytes but declares $length"
          )
        }
      }

      RegionEncoding.DELTA -> {
        val instructions = tree.window(tree.readVarInt())
        state.literalCursor += state.delta.apply(
          source = state.source,
          instructions = instructions,
          literals = state.literals,
          literalFrom = state.literalCursor,
          out = out,
          length = length.withinVersionOne("Region length")
        )
        instructions.requireConsumed("Delta instructions")
      }

      RegionEncoding.RAW -> {
        val span = length.withinVersionOne("Region length")
        if (span > state.literals.size - state.literalCursor) {
          throw KiffException.InvalidPatch("Raw region reads past the literal stream")
        }
        out.write(state.literals, state.literalCursor, state.literalCursor + span)
        state.literalCursor += span
      }

      RegionEncoding.TEXT -> {
        val sourceFrom = tree.readVarLong()
        val sourceLength = tree.readVarLong()
        val edits = tree.window(tree.readVarInt())
        state.literalCursor += TextRegion.apply(
          source = state.read(sourceFrom, sourceLength, "Text"),
          edits = edits,
          literals = state.literals,
          literalFrom = state.literalCursor,
          out = out,
          length = length.withinVersionOne("Region length")
        )
        edits.requireConsumed("Text edits")
      }

      RegionEncoding.COLUMNS -> {
        // A columns region builds its output whole before emitting it, so one inside another, at
        // any depth, would hold a region's worth again for every level it nests.
        if (!onTheRestore) {
          throw KiffException.InvalidPatch("Column region nested inside another column region")
        }
        val sourceFrom = tree.readVarLong()
        val sourceLength = tree.readVarLong()
        val count = tree.readByte()
        val widths = List(count) { tree.readByte() }
        val span = length.withinVersionOne("Region length")
        if (!ColumnTransform.suits(widths, span)) {
          throw KiffException.InvalidPatch("Column region declares a row this cannot describe")
        }
        val sourceBytes = state.read(sourceFrom, sourceLength, "Column")
        val rearranged = ColumnTransform.forward(sourceBytes, 0, sourceBytes.size, widths)
        val scratch = ByteArray(span)
        val nested = state.over(ByteArraySource(rearranged))
        val written =
          applyNode(tree, nested, ScratchRestoreTarget(scratch), length, depth + 1, false)
        if (written != length) {
          throw KiffException.InvalidPatch("Column region produced $written of $length bytes")
        }
        state.literalCursor = nested.literalCursor
        val restored = ColumnTransform.inverse(scratch, widths, span)
        out.write(restored, 0, span)
      }
    }

    val produced = out.position - before
    if (produced != length) {
      throw KiffException.InvalidPatch(
        "Region [$startedAt, ${startedAt + length}) produced $produced of its $length bytes"
      )
    }
    if (expected != null && onTheRestore) {
      val actual = state.checked.finishRegion()
      if (actual != expected) {
        throw KiffException.VerificationFailed(
          "Region [$startedAt, ${startedAt + length}) described as " +
            "${encoding.name.lowercase()} restored to ${actual.toHex()}, not the " +
            "${expected.toHex()} it was built from"
        )
      }
    }
    return length
  }

  /** What a restore carries from one region to the next. */
  private class Restore(
    val source: SeekableSource,
    val literals: ByteArray,
    val checked: CheckedRestoreTarget,
    val delta: DeltaReader
  ) {
    /** Where the next region's content starts; regions consume the stream in tree order. */
    var literalCursor: Int = 0

    /** The same restore against a different source, for a region that rearranges its own. */
    fun over(other: SeekableSource) = Restore(other, literals, checked, delta).also {
      it.literalCursor = literalCursor
    }

    /** Reads a source range in, for the encodings that cannot work a chunk at a time. */
    fun read(from: Long, length: Long, what: String): ByteArray {
      if (from < 0 || length < 0 || length > source.size - from) {
        throw KiffException.InvalidPatch("$what region names a source range outside the source")
      }
      val bytes = ByteArray(length.withinVersionOne("$what source length"))
      source.readFully(from, bytes)
      return bytes
    }
  }

  /**
   * A length a version-1 reader holds as an [Int]: one region, one source range, the tree and the
   * content are each at most `Int.MAX_VALUE` bytes. A larger target is a composite of regions that
   * fit, so a patch past this is not one version 1 can express, rather than one too big to apply.
   */
  private fun Long.withinVersionOne(what: String): Int {
    if (this < 0 || this > Int.MAX_VALUE) {
      throw KiffException.InvalidPatch("$what $this is longer than version 1 allows")
    }
    return toInt()
  }
}
