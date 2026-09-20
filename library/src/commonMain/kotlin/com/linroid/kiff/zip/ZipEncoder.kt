package com.linroid.kiff.zip

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaScanner
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.Lzss
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.format.beatsStoring
import com.linroid.kiff.format.structureBytes
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.region.BinaryRegionPlanner
import com.linroid.kiff.region.RegionAlgorithm
import com.linroid.kiff.region.RegionInfo
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.region.RegionPlanner
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.text.LineDiffAlgorithm
import com.linroid.kiff.text.MyersDiffAlgorithm
import com.linroid.kiff.text.TextRegion

/**
 * Describes a target archive as a tree of regions.
 *
 * Walking the structure instead of the raw bytes means every target region is compared against the
 * region it actually corresponds to: an unchanged entry becomes a single copy without being scanned
 * at all, and a changed entry is indexed against its counterpart alone, which buys a finer stride
 * and far fewer hash collisions than indexing the whole archive.
 *
 * A changed entry nests one level further, into its local header, its data and any data descriptor.
 * That is not bookkeeping: it is what lets the *data* be planned on its own, so an entry holding
 * text can be diffed as lines while the header bytes around it stay byte-level.
 */
internal class ZipEncoder(
  private val sourceSource: ByteArraySource,
  private val sourceLayout: ZipLayout,
  private val targetSource: ByteArraySource,
  private val targetLayout: ZipLayout,
  private val options: ZipEncodeOptions,
  private val algorithm: DeltaAlgorithm
) {

  private val source: ByteArray = sourceSource.bytes
  private val target: ByteArray = targetSource.bytes

  /** Built only if some target region has no counterpart to be indexed against. */
  private val wholeSourceScanner by lazy { algorithm.scanner(sourceSource) }

  fun encode(literals: ByteWriter, recorder: RegionRecorder? = null): RegionNode {
    val children = ArrayList<Encoded>(targetLayout.regions.size)
    for (region in targetLayout.regions) {
      val literalsBefore = literals.size
      val encoded = encodeRegion(region, literals)
      children.add(encoded)
      recorder?.record(
        labelOf(region),
        kindOf(region),
        region.size.toLong(),
        encoded.node.structureBytes(),
        (literals.size - literalsBefore).toLong()
      )
    }
    return RegionNode.Composite(coalesce(children))
  }

  /**
   * Merges neighbouring regions that are each a plain copy from consecutive source bytes.
   *
   * Every node costs its length, its encoding and its instruction before it describes anything, so
   * an archive of mostly untouched entries would otherwise pay that ten or so times per entry to
   * say the same thing a single copy says once. Regions tile the target in order, so a run of
   * copies whose source offsets are also consecutive is exactly one copy.
   */
  private fun coalesce(children: List<Encoded>): List<RegionNode> {
    val out = ArrayList<RegionNode>(children.size)
    var runFrom = -1L
    var runLength = 0L

    fun flush() {
      if (runLength > 0) out.add(copyNode(runFrom, runLength))
      runFrom = -1L
      runLength = 0
    }

    for (child in children) {
      val copy = child.copy
      when {
        copy == null -> {
          flush()
          out.add(child.node)
        }
        runLength > 0 && runFrom + runLength == copy.sourceFrom -> runLength += copy.length
        else -> {
          flush()
          runFrom = copy.sourceFrom
          runLength = copy.length
        }
      }
    }
    flush()
    return out
  }

  /** A region and, when it is nothing but one copy, the source run it copies. */
  private class Encoded(val node: RegionNode, val copy: CopyRun? = null)

  private class CopyRun(val sourceFrom: Long, val length: Long)

  private fun encodeRegion(region: ZipRegion, literals: ByteWriter): Encoded = when (region) {
    is ZipRegion.Record -> encodeRecord(region.entry, literals)
    is ZipRegion.Gap -> encodeGap(region, literals)
    is ZipRegion.Directory -> encodeRange(
      CENTRAL_DIRECTORY,
      RegionKind.INDEX,
      sourceLayout.directory.from,
      sourceLayout.directory.to,
      region.from,
      region.to,
      literals
    )
  }

  private fun encodeRecord(entry: ZipEntry, literals: ByteWriter): Encoded {
    val recordSize = entry.recordEnd - entry.localHeaderOffset
    val counterpart = counterpartOf(entry)

    if (counterpart == null) {
      // Searching the whole archive means indexing it; only worth it for a substantial entry.
      if (recordSize < MIN_SEARCHABLE_RECORD) {
        return Encoded(raw(entry.localHeaderOffset, entry.recordEnd, literals))
      }
      return Encoded(cheaperOfRaw(entry.localHeaderOffset, entry.recordEnd, literals) { scratch ->
        val writer = DeltaWriter(source, scratch)
        scan(wholeSourceScanner, entry.localHeaderOffset, entry.recordEnd, writer, alignment = 0)
        RegionNode.Delta(recordSize.toLong(), writer.finishInstructions())
      })
    }

    if (counterpart.recordEnd - counterpart.localHeaderOffset == recordSize &&
      regionsEqual(counterpart.localHeaderOffset, entry.localHeaderOffset, recordSize)
    ) {
      return copyOf(counterpart.localHeaderOffset.toLong(), recordSize.toLong())
    }

    // The record's three parts are described separately, so the data can pick its own encoding.
    val children = ArrayList<RegionNode>(3)
    // A record split three ways is still one region to its parent, so it never coalesces.
    addRange(
      children, "${entry.name} (header)", RegionKind.INDEX,
      counterpart.localHeaderOffset, counterpart.dataOffset,
      entry.localHeaderOffset, entry.dataOffset,
      literals
    )
    addRange(
      children, entry.name, RegionKind.CONTENT,
      counterpart.dataOffset, counterpart.dataOffset + counterpart.compressedSize,
      entry.dataOffset, entry.dataOffset + entry.compressedSize,
      literals
    )
    addRange(
      children, "${entry.name} (descriptor)", RegionKind.INDEX,
      counterpart.dataOffset + counterpart.compressedSize, counterpart.recordEnd,
      entry.dataOffset + entry.compressedSize, entry.recordEnd,
      literals
    )
    return Encoded(RegionNode.Composite(children))
  }

  private fun encodeGap(gap: ZipRegion.Gap, literals: ByteWriter): Encoded {
    val counterpart = sourceLayout.gapsByKey[gap.key]
      ?: return Encoded(raw(gap.from, gap.to, literals))
    return encodeRange(
      labelOfGap(gap.key),
      RegionKind.GAP,
      counterpart.from,
      counterpart.to,
      gap.from,
      gap.to,
      literals
    )
  }

  private fun addRange(
    into: MutableList<RegionNode>,
    name: String,
    kind: RegionKind,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter
  ) {
    if (targetTo <= targetFrom) return
    into.add(encodeRange(name, kind, sourceFrom, sourceTo, targetFrom, targetTo, literals).node)
  }

  /** Describes `target[targetFrom, targetTo)` against `source[sourceFrom, sourceTo)`. */
  private fun encodeRange(
    name: String,
    kind: RegionKind,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter
  ): Encoded {
    val span = targetTo - targetFrom
    if (span == sourceTo - sourceFrom && regionsEqual(sourceFrom, targetFrom, span)) {
      return copyOf(sourceFrom.toLong(), span.toLong())
    }
    val info = RegionInfo(
      name = name,
      kind = kind,
      targetBytes = span.toLong(),
      sourceBytes = (sourceTo - sourceFrom).toLong(),
      looksLikeText = looksLikeText(source, sourceFrom, sourceTo) &&
        looksLikeText(target, targetFrom, targetTo)
    )
    val plan = options.planner.plan(info)
    if (plan == RegionAlgorithm.RAW) return Encoded(raw(targetFrom, targetTo, literals))

    // The plan is a shortlist, not a verdict. Where it nominates something other than bytes, both
    // are built and the smaller wins - otherwise a guess about the content decides the patch size,
    // which is how a region that packs beautifully gets thrown away for one that does not.
    val binary = binaryCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo)
    val best = if (plan == RegionAlgorithm.TEXT) {
      val text = textCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo)
      if (text != null && text.packedCost() < binary.packedCost()) text else binary
    } else {
      binary
    }

    if (!beatsStoring(best.node.structureBytes(), best.literals, target, targetFrom, targetTo)) {
      return Encoded(raw(targetFrom, targetTo, literals))
    }
    literals.writeBytes(best.literals)
    return Encoded(best.node)
  }

  private fun binaryCandidate(
    span: Int,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int
  ): Candidate {
    val scratch = ByteWriter(span.coerceIn(64, 1 shl 16))
    val writer = DeltaWriter(source, scratch)
    algorithm.scanner(sourceSource, sourceFrom.toLong(), sourceTo.toLong()).use { scanner ->
      scan(scanner, targetFrom, targetTo, writer, alignment = sourceFrom - targetFrom)
    }
    return Candidate(
      RegionNode.Delta(span.toLong(), writer.finishInstructions()),
      scratch.toByteArray()
    )
  }

  private fun textCandidate(
    span: Int,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int
  ): Candidate? {
    val scratch = ByteWriter(span.coerceIn(64, 1 shl 16))
    val edits = TextRegion.encode(
      options.lineAlgorithm,
      source, sourceFrom, sourceTo,
      target, targetFrom, targetTo,
      scratch
    ) ?: return null
    return Candidate(
      RegionNode.Text(
        targetLength = span.toLong(),
        sourceFrom = sourceFrom.toLong(),
        sourceLength = (sourceTo - sourceFrom).toLong(),
        edits = edits
      ),
      scratch.toByteArray()
    )
  }

  /**
   * One way of describing a region, and what it would add to the patch.
   *
   * Cost is measured on the packed content, because that is what reaches the patch. An unpacked
   * comparison systematically prefers whichever encoding emits fewer bytes over whichever emits
   * more compressible ones, and on real content those are rarely the same encoding.
   */
  private class Candidate(val node: RegionNode, val literals: ByteArray) {
    fun packedCost(): Long =
      node.structureBytes() + if (literals.isEmpty()) 0 else Lzss.compress(literals).size
  }

  /**
   * Encodes into a scratch literal buffer and keeps the result only if it beat storing the bytes.
   *
   * A delta against a poor counterpart - a recompressed entry, an image that changed wholesale -
   * routinely costs more than the region itself. Measuring rather than assuming is what stops a
   * patch paying for a search that did not pay off, and it is also what makes a new region
   * encoding safe to try: at worst it is discarded here.
   */
  private inline fun cheaperOfRaw(
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter,
    encode: (ByteWriter) -> RegionNode
  ): RegionNode {
    val span = targetTo - targetFrom
    val scratch = ByteWriter(span.coerceIn(64, 1 shl 16))
    val node = encode(scratch)
    val candidate = scratch.toByteArray()
    if (!beatsStoring(node.structureBytes(), candidate, target, targetFrom, targetTo)) {
      return raw(targetFrom, targetTo, literals)
    }
    literals.writeBytes(candidate)
    return node
  }

  private fun raw(from: Int, to: Int, literals: ByteWriter): RegionNode {
    literals.writeBytes(target, from, to)
    return RegionNode.Raw((to - from).toLong())
  }

  /** A region that is byte for byte its counterpart: one COPY, nothing in the literal stream. */
  private fun copyOf(sourceOffset: Long, length: Long) =
    Encoded(copyNode(sourceOffset, length), CopyRun(sourceOffset, length))

  private fun copyNode(sourceOffset: Long, length: Long): RegionNode {
    // A copy emits no literals, so the stream it is handed is never written to.
    val writer = DeltaWriter(source, ByteWriter(16))
    writer.copy(sourceOffset, length)
    return RegionNode.Delta(length, writer.finishInstructions())
  }

  /**
   * [alignment] tells the scanner which source offset this target region starts out parallel to, so
   * a changed entry can be difference-encoded from its counterpart's first byte.
   */
  private fun scan(
    scanner: DeltaScanner,
    targetFrom: Int,
    targetTo: Int,
    writer: DeltaWriter,
    alignment: Int
  ) {
    scanner.scan(targetSource, targetFrom.toLong(), targetTo.toLong(), writer, alignment.toLong())
  }

  private fun labelOf(region: ZipRegion): String = when (region) {
    is ZipRegion.Record -> region.entry.name
    is ZipRegion.Gap -> labelOfGap(region.key)
    is ZipRegion.Directory -> CENTRAL_DIRECTORY
  }

  private fun labelOfGap(key: ZipGapKey): String = when {
    key.beforeDirectory -> BEFORE_DIRECTORY
    key.afterEntry == null -> PREAMBLE
    else -> "(gap after ${key.afterEntry})"
  }

  private fun kindOf(region: ZipRegion): RegionKind = when (region) {
    is ZipRegion.Record -> RegionKind.CONTENT
    is ZipRegion.Gap -> RegionKind.GAP
    is ZipRegion.Directory -> RegionKind.INDEX
  }

  private fun counterpartOf(entry: ZipEntry): ZipEntry? =
    sourceLayout.entriesByName[entry.name]
      ?: sourceLayout.entriesByContent[
        ZipLayout.ContentKey(entry.crc32, entry.compressedSize, entry.method)
      ]
      ?: options.pairUnmatched(entry, sourceLayout)

  private fun regionsEqual(sourceFrom: Int, targetFrom: Int, length: Int): Boolean {
    if (sourceFrom < 0 || sourceFrom + length > source.size) return false
    if (targetFrom + length > target.size) return false
    for (i in 0 until length) {
      if (source[sourceFrom + i] != target[targetFrom + i]) return false
    }
    return true
  }

  private companion object {
    const val MIN_SEARCHABLE_RECORD = 64 * 1024

    /** How far into a region the NUL check looks before it accepts the bytes as text. */
    const val TEXT_SNIFF = 8 * 1024

    const val CENTRAL_DIRECTORY = "(central directory)"
    const val PREAMBLE = "(preamble)"

    /** In an APK this is where the signing block sits. */
    const val BEFORE_DIRECTORY = "(gap before central directory)"

    /**
     * No NUL byte near the start of the range.
     *
     * Deliberately conservative and deliberately cheap: text never holds a NUL, and the binary
     * formats that matter here carry one within the first few hundred bytes. Being wrong only ever
     * costs an encoding attempt, because the result still has to beat storing the bytes outright.
     */
    fun looksLikeText(bytes: ByteArray, from: Int, to: Int): Boolean {
      if (to <= from) return false
      val end = minOf(to, from + TEXT_SNIFF)
      for (i in from until end) {
        if (bytes[i] == 0.toByte()) return false
      }
      return true
    }
  }
}

/** Format-specific knowledge a [ZipEncoder] can be given. */
internal class ZipEncodeOptions(
  /**
   * Last-resort pairing for a target entry that matched no source entry by name or by content -
   * where an APK can say that `classes4.dex` belongs next to `classes3.dex`.
   */
  val pairUnmatched: (ZipEntry, ZipLayout) -> ZipEntry? = { _, _ -> null },
  val planner: RegionPlanner = BinaryRegionPlanner,
  val lineAlgorithm: LineDiffAlgorithm = MyersDiffAlgorithm()
)
