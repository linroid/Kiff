package com.linroid.kiff.container

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaScanner
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.Lzss
import com.linroid.kiff.format.MAX_REGION_DEPTH
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.format.beatsStoring
import com.linroid.kiff.format.structureBytes
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.region.RegionAlgorithm
import com.linroid.kiff.region.RegionInfo
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.region.RegionPlanner
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.text.LineDiffAlgorithm
import com.linroid.kiff.text.TextRegion

/**
 * Turns two files into a region tree, knowing nothing about any file format.
 *
 * Everything format-specific lives behind [ContainerFormat]: this decides *how* a region is
 * described, never *what* the regions are. That split is the point - a new container format is a
 * class and a registry entry, and neither this driver, nor the node encodings, nor the decoder
 * moves.
 *
 * Decomposing a nested region competes with describing it whole, like every other choice here.
 * Structure is not free - each node costs its length, its encoding and its instruction - and a
 * byte search that can copy from anywhere in the source is already very good at finding moved and
 * edited content, so taking a region apart is a bet that does not always pay. Measured rather than
 * assumed: the loser is discarded.
 */
internal class ContainerEncoder(
  private val sourceSource: ByteArraySource,
  private val targetSource: ByteArraySource,
  private val algorithm: DeltaAlgorithm,
  private val planner: RegionPlanner,
  private val lineAlgorithm: LineDiffAlgorithm,
  private val registry: ContainerRegistry
) {

  private val source: ByteArray = sourceSource.bytes
  private val target: ByteArray = targetSource.bytes

  /** Built only if some target region has no counterpart to be indexed against. */
  private val wholeSourceScanner by lazy { algorithm.scanner(sourceSource) }

  /**
   * Describes the whole target.
   *
   * [rootFormat] is applied without asking [ContainerFormat.detect], because the patcher already
   * decided: a zip patcher on a zip with a preamble should decompose it even though the file does
   * not begin with a local header. Detection is for regions found along the way.
   */
  fun encode(
    rootFormat: ContainerFormat?,
    literals: ByteWriter,
    recorder: RegionRecorder?
  ): RegionNode {
    val children = rootFormat
      ?.decompose(target, 0, target.size)
      .orEmpty()
      .takeIf { it.size > 1 }

    if (children == null) {
      // Nothing claims it, so it is one region and gets described as one.
      val literalsBefore = literals.size
      val node = leaf(WHOLE_FILE, RegionKind.WHOLE, 0, source.size, 0, target.size, literals)
      recorder?.record(
        WHOLE_FILE,
        RegionKind.WHOLE,
        target.size.toLong(),
        node.structureBytes(),
        (literals.size - literalsBefore).toLong()
      )
      return node
    }

    val sourceChildren = rootFormat!!.decompose(source, 0, source.size)
    return composite(rootFormat, sourceChildren, children, literals, recorder, depth = 0)
  }

  /** Encodes a list of paired children into one composite, recording each as it goes. */
  private fun composite(
    format: ContainerFormat,
    sourceChildren: List<Child>,
    targetChildren: List<Child>,
    literals: ByteWriter,
    recorder: RegionRecorder?,
    depth: Int
  ): RegionNode {
    val pairing = Pairing(format, sourceChildren)
    val encoded = ArrayList<Encoded>(targetChildren.size)
    for (child in targetChildren) {
      val literalsBefore = literals.size
      val node = encodeChild(child, pairing.counterpartOf(child), literals, depth)
      encoded.add(node)
      recorder?.record(
        child.name,
        child.kind,
        child.size.toLong(),
        node.node.structureBytes(),
        (literals.size - literalsBefore).toLong()
      )
    }
    return RegionNode.Composite(coalesce(encoded))
  }

  private fun encodeChild(
    child: Child,
    counterpart: Child?,
    literals: ByteWriter,
    depth: Int
  ): Encoded {
    if (counterpart == null) {
      // Searching the whole source means indexing it; only worth it for a substantial region.
      if (child.size < MIN_SEARCHABLE) return Encoded(raw(child.from, child.to, literals))
      return Encoded(
        keepIfItBeatsStoring(child.from, child.to, literals) { scratch ->
          val writer = DeltaWriter(source, scratch)
          scan(wholeSourceScanner, child.from, child.to, writer, alignment = 0)
          RegionNode.Delta(child.size.toLong(), writer.finishInstructions())
        }
      )
    }

    if (identical(counterpart.from, child.from, child.size, counterpart.size)) {
      return copyOf(counterpart.from.toLong(), child.size.toLong())
    }

    // A child with no framing is just its payload, and needs no composite around it.
    if (child.isBare && counterpart.isBare) {
      return Encoded(
        describe(child.name, child.kind, counterpart, child, literals, depth)
      )
    }

    // Framing is compared as the bytes it is; only the payload gets to choose for itself.
    val parts = ArrayList<RegionNode>(3)
    addPart(
      parts, "${child.name} (header)", RegionKind.INDEX,
      counterpart.from, counterpart.contentFrom, child.from, child.contentFrom, literals, depth
    )
    parts.add(describe(child.name, child.kind, counterpart, child, literals, depth))
    addPart(
      parts, "${child.name} (descriptor)", RegionKind.INDEX,
      counterpart.contentTo, counterpart.to, child.contentTo, child.to, literals, depth
    )
    return Encoded(RegionNode.Composite(parts))
  }

  /**
   * Describes a child's payload, recursing when the payload is itself a container.
   *
   * Only stored payloads can be recursed into. A compressed one would have to be decoded to be
   * compared and re-encoded byte for byte to be restored, and nothing here can do the second half
   * yet, so it is described as the opaque bytes it is.
   */
  private fun describe(
    name: String,
    kind: RegionKind,
    counterpart: Child,
    child: Child,
    literals: ByteWriter,
    depth: Int
  ): RegionNode {
    val targetFrom = child.contentFrom
    val targetTo = child.contentTo
    val sourceFrom = counterpart.contentFrom
    val sourceTo = counterpart.contentTo
    if (targetTo <= targetFrom) return RegionNode.Raw(0)

    val decomposed = if (
      child.storage == Storage.STORED &&
      counterpart.storage == Storage.STORED &&
      depth + 1 < MAX_REGION_DEPTH
    ) {
      decompose(sourceFrom, sourceTo, targetFrom, targetTo, depth)
    } else {
      null
    }

    val flatScratch = ByteWriter((targetTo - targetFrom).coerceIn(64, 1 shl 16))
    val flat = leaf(name, kind, sourceFrom, sourceTo, targetFrom, targetTo, flatScratch)
    val flatBytes = flatScratch.toByteArray()

    if (decomposed != null && packedCost(decomposed.node, decomposed.literals) <
      packedCost(flat, flatBytes)
    ) {
      literals.writeBytes(decomposed.literals)
      return decomposed.node
    }
    literals.writeBytes(flatBytes)
    return flat
  }

  /** Builds the decomposed form of a range into its own buffer, so it can be weighed and dropped. */
  private fun decompose(
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    depth: Int
  ): Candidate? {
    val format = registry.formatFor(target, targetFrom, targetTo) ?: return null
    val targetChildren = format.decompose(target, targetFrom, targetTo)
    if (targetChildren.size <= 1) return null
    val sourceChildren = format.decompose(source, sourceFrom, sourceTo)
    if (sourceChildren.isEmpty()) return null

    val scratch = ByteWriter((targetTo - targetFrom).coerceIn(64, 1 shl 16))
    val node = composite(format, sourceChildren, targetChildren, scratch, null, depth + 1)
    return Candidate(node, scratch.toByteArray())
  }

  private fun packedCost(node: RegionNode, literals: ByteArray): Long =
    node.structureBytes() + if (literals.isEmpty()) 0 else Lzss.compress(literals).size

  private fun addPart(
    into: MutableList<RegionNode>,
    name: String,
    kind: RegionKind,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter,
    depth: Int
  ) {
    if (targetTo <= targetFrom) return
    into.add(leaf(name, kind, sourceFrom, sourceTo, targetFrom, targetTo, literals))
  }

  /**
   * Describes one range that is not decomposed further.
   *
   * The plan is a shortlist, not a verdict: where it nominates something other than bytes, both are
   * built and the smaller wins, and neither is kept if storing the bytes outright would have been
   * smaller still. A planner can therefore only ever cost encode time, never patch size.
   */
  private fun leaf(
    name: String,
    kind: RegionKind,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter
  ): RegionNode {
    val span = targetTo - targetFrom
    if (identical(sourceFrom, targetFrom, span, sourceTo - sourceFrom)) {
      return copyNode(sourceFrom.toLong(), span.toLong())
    }
    val info = RegionInfo(
      name = name,
      kind = kind,
      targetBytes = span.toLong(),
      sourceBytes = (sourceTo - sourceFrom).toLong(),
      looksLikeText = looksLikeText(source, sourceFrom, sourceTo) &&
        looksLikeText(target, targetFrom, targetTo)
    )
    val plan = planner.plan(info)
    if (plan == RegionAlgorithm.RAW) return raw(targetFrom, targetTo, literals)

    val binary = binaryCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo)
    val best = if (plan == RegionAlgorithm.TEXT) {
      val text = textCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo)
      if (text != null && text.packedCost() < binary.packedCost()) text else binary
    } else {
      binary
    }

    if (!beatsStoring(best.node.structureBytes(), best.literals, target, targetFrom, targetTo)) {
      return raw(targetFrom, targetTo, literals)
    }
    literals.writeBytes(best.literals)
    return best.node
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
      lineAlgorithm,
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

  private inline fun keepIfItBeatsStoring(
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter,
    encode: (ByteWriter) -> RegionNode
  ): RegionNode {
    val scratch = ByteWriter((targetTo - targetFrom).coerceIn(64, 1 shl 16))
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

  private fun copyOf(sourceOffset: Long, length: Long) =
    Encoded(copyNode(sourceOffset, length), CopyRun(sourceOffset, length))

  private fun copyNode(sourceOffset: Long, length: Long): RegionNode {
    // A copy emits no content, so the stream it is handed is never written to.
    val writer = DeltaWriter(source, ByteWriter(16))
    writer.copy(sourceOffset, length)
    return RegionNode.Delta(length, writer.finishInstructions())
  }

  /**
   * Merges neighbouring regions that are each a plain copy from consecutive source bytes.
   *
   * Every node costs its length, its encoding and its instruction before it describes anything, so
   * an archive of mostly untouched entries would otherwise pay that ten or so times per entry to
   * say the same thing a single copy says once.
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

  private fun scan(
    scanner: DeltaScanner,
    targetFrom: Int,
    targetTo: Int,
    writer: DeltaWriter,
    alignment: Int
  ) {
    scanner.scan(targetSource, targetFrom.toLong(), targetTo.toLong(), writer, alignment.toLong())
  }

  private fun identical(sourceFrom: Int, targetFrom: Int, length: Int, sourceLength: Int): Boolean {
    if (length != sourceLength) return false
    if (sourceFrom < 0 || sourceFrom + length > source.size) return false
    if (targetFrom + length > target.size) return false
    for (i in 0 until length) {
      if (source[sourceFrom + i] != target[targetFrom + i]) return false
    }
    return true
  }

  /** Pairs target children to source children: by name, then by content, then by the format. */
  private class Pairing(private val format: ContainerFormat, private val source: List<Child>) {
    private val byName = source.associateBy { it.name }
    private val byContent: Map<Any, Child> = buildMap {
      val duplicated = HashSet<Any>()
      for (child in source) {
        val key = child.contentKey ?: continue
        if (!duplicated.add(key)) continue
        if (put(key, child) != null) remove(key)
      }
    }

    fun counterpartOf(child: Child): Child? =
      byName[child.name]
        ?: child.contentKey?.let { byContent[it] }
        ?: format.pairUnmatched(child, source)
  }

  /** A region and, when it is nothing but one copy, the source run it copies. */
  private class Encoded(val node: RegionNode, val copy: CopyRun? = null)

  private class CopyRun(val sourceFrom: Long, val length: Long)

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

  private companion object {
    const val WHOLE_FILE = "(whole file)"
    const val MIN_SEARCHABLE = 64 * 1024

    /** How far into a region the NUL check looks before it accepts the bytes as text. */
    const val TEXT_SNIFF = 8 * 1024

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
