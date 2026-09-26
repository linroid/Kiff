package com.linroid.kiff.container

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaScanner
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.ColumnTransform
import com.linroid.kiff.format.LzHuffman
import com.linroid.kiff.format.MAX_REGION_DEPTH
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.format.beatsStoring
import com.linroid.kiff.format.encoding
import com.linroid.kiff.format.structureBytes
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.region.RegionAlgorithm
import com.linroid.kiff.region.RegionCost
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
    // Both sides have to be the format, as they do for a nested region. Taken apart against a
    // source that could not be, the target's parts would have nothing to pair with, and each would
    // be stored or searched on its own - far larger than one search over the whole file.
    val sourceChildren = children?.let { rootFormat!!.decompose(source, 0, source.size) }

    if (children == null || sourceChildren.isNullOrEmpty()) {
      // Nothing claims it, so it is one region and gets described as one.
      val literalsBefore = literals.size
      val node = leaf(WHOLE_FILE, RegionKind.WHOLE, 0, source.size, 0, target.size, literals)
      recorder?.record(
        RegionCost(
          name = WHOLE_FILE,
          kind = RegionKind.WHOLE,
          encoding = node.encoding(),
          targetBytes = target.size.toLong(),
          instructionBytes = node.structureBytes(),
          literalBytes = (literals.size - literalsBefore).toLong()
        )
      )
      return node
    }

    val built = composite(rootFormat!!, sourceChildren, children, literals, depth = 0)
    if (recorder != null) for (cost in built.costs) recorder.record(cost)
    return built.node
  }

  /**
   * Encodes a list of paired children into one composite, collecting what each of them cost.
   *
   * The costs come back rather than going into a recorder, because a composite built here may yet
   * be thrown away - it is weighed against describing the same bytes whole. Reporting as it went
   * would attribute a patch to regions that are not in it.
   */
  private fun composite(
    format: ContainerFormat,
    sourceChildren: List<Child>,
    targetChildren: List<Child>,
    literals: ByteWriter,
    depth: Int,
    inherited: DeltaScanner? = null
  ): Built {
    val pairing = Pairing(format, sourceChildren)
    val groups = Groups(sourceChildren, inherited)
    val encoded = ArrayList<Encoded>(targetChildren.size)
    val costs = ArrayList<RegionCost>(targetChildren.size)
    for (child in targetChildren) {
      val before = literals.size
      val one = encodeChild(child, pairing.counterpartOf(child), groups, literals, depth)
      encoded.add(one)
      costs.add(
        RegionCost(
          name = child.name,
          kind = child.kind,
          encoding = one.node.encoding(),
          targetBytes = child.size.toLong(),
          instructionBytes = one.node.structureBytes(),
          literalBytes = (literals.size - before).toLong(),
          children = one.children
        )
      )
    }
    groups.close()
    return Built(RegionNode.Composite(coalesce(encoded)), costs)
  }

  private fun encodeChild(
    child: Child,
    counterpart: Child?,
    groups: Groups,
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
      val payload = describe(child, counterpart, groups, literals, depth)
      return Encoded(payload.node, children = payload.costs)
    }

    // Framing is compared as the bytes it is; only the payload gets to choose for itself.
    val parts = ArrayList<RegionNode>(3)
    val costs = ArrayList<RegionCost>(3)
    addPart(
      parts, costs, "${child.name} (header)", RegionKind.INDEX,
      counterpart.from, counterpart.contentFrom, child.from, child.contentFrom, literals
    )

    val before = literals.size
    val payload = describe(child, counterpart, groups, literals, depth)
    parts.add(payload.node)
    costs.add(
      RegionCost(
        // Distinguishable from the record around it, which is reported by its bare name.
        name = "${child.name} (data)",
        kind = child.kind,
        encoding = payload.node.encoding(),
        targetBytes = (child.contentTo - child.contentFrom).toLong(),
        instructionBytes = payload.node.structureBytes(),
        literalBytes = (literals.size - before).toLong(),
        children = payload.costs
      )
    )

    addPart(
      parts, costs, "${child.name} (descriptor)", RegionKind.INDEX,
      counterpart.contentTo, counterpart.to, child.contentTo, child.to, literals
    )
    return Encoded(RegionNode.Composite(parts), children = costs)
  }

  /**
   * Describes a child's payload, recursing when the payload is itself a container.
   *
   * Only stored payloads can be recursed into. A compressed one would have to be decoded to be
   * compared and re-encoded byte for byte to be restored, and nothing here can do the second half
   * yet, so it is described as the opaque bytes it is.
   */
  private fun describe(
    child: Child,
    counterpart: Child,
    groups: Groups,
    literals: ByteWriter,
    depth: Int
  ): Built {
    val name = child.name
    val kind = child.kind
    val targetFrom = child.contentFrom
    val targetTo = child.contentTo
    val sourceFrom = counterpart.contentFrom
    val sourceTo = counterpart.contentTo
    if (targetTo <= targetFrom) return Built(RegionNode.Raw(0), emptyList())

    val decomposed = if (
      child.storage == Storage.STORED &&
      counterpart.storage == Storage.STORED &&
      depth + 1 < MAX_REGION_DEPTH
    ) {
      decompose(sourceFrom, sourceTo, targetFrom, targetTo, depth, groups.scannerFor(child.group))
    } else {
      null
    }

    val flatScratch = ByteWriter((targetTo - targetFrom).coerceIn(64, 1 shl 16))
    val flat = leaf(
      name, kind, sourceFrom, sourceTo, targetFrom, targetTo, flatScratch,
      groups.scannerFor(child.group), child.columns
    )
    val flatBytes = flatScratch.toByteArray()

    if (decomposed != null && packedCost(decomposed.node, decomposed.literals) <
      packedCost(flat, flatBytes)
    ) {
      literals.writeBytes(decomposed.literals)
      return Built(decomposed.node, decomposed.costs)
    }
    literals.writeBytes(flatBytes)
    return Built(flat, emptyList())
  }

  /** Builds the decomposed form of a range into its own buffer, so it can be weighed and dropped. */
  private fun decompose(
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    depth: Int,
    inherited: DeltaScanner?
  ): Candidate? {
    val format = registry.formatFor(target, targetFrom, targetTo) ?: return null
    val targetChildren = format.decompose(target, targetFrom, targetTo)
    if (targetChildren.size <= 1) return null
    val sourceChildren = format.decompose(source, sourceFrom, sourceTo)
    if (sourceChildren.isEmpty()) return null

    val scratch = ByteWriter((targetTo - targetFrom).coerceIn(64, 1 shl 16))
    val built = composite(format, sourceChildren, targetChildren, scratch, depth + 1, inherited)
    return Candidate(built.node, scratch.toByteArray(), built.costs)
  }

  private fun packedCost(node: RegionNode, literals: ByteArray): Long =
    node.structureBytes() + if (literals.isEmpty()) 0 else LzHuffman.compress(literals).size

  private fun addPart(
    into: MutableList<RegionNode>,
    costs: MutableList<RegionCost>,
    name: String,
    kind: RegionKind,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter
  ) {
    if (targetTo <= targetFrom) return
    val before = literals.size
    val node = leaf(name, kind, sourceFrom, sourceTo, targetFrom, targetTo, literals)
    into.add(node)
    costs.add(
      RegionCost(
        name = name,
        kind = kind,
        encoding = node.encoding(),
        targetBytes = (targetTo - targetFrom).toLong(),
        instructionBytes = node.structureBytes(),
        literalBytes = (literals.size - before).toLong()
      )
    )
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
    literals: ByteWriter,
    shared: DeltaScanner? = null,
    columns: List<Int>? = null
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

    var best = binaryCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo, shared)
    if (plan == RegionAlgorithm.TEXT) {
      val text = textCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo)
      if (text != null && text.packedCost() < best.packedCost()) best = text
    }
    // A table only its format could recognise gets one more way of being read.
    if (columns != null) {
      val rearranged = columnsCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo, columns)
      if (rearranged != null && rearranged.packedCost() < best.packedCost()) best = rearranged
    }

    if (!beatsStoring(best.node.structureBytes(), best.literals, target, targetFrom, targetTo)) {
      return raw(targetFrom, targetTo, literals)
    }
    literals.writeBytes(best.literals)
    return best.node
  }

  /**
   * [shared] is the index for this region's group, when it has one. The alignment still points at
   * the counterpart, so a region that mostly did not change is still difference-encoded from the
   * right place; the wider index only adds somewhere else to look for what did move.
   */
  private fun binaryCandidate(
    span: Int,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    shared: DeltaScanner?
  ): Candidate {
    val scratch = ByteWriter(span.coerceIn(64, 1 shl 16))
    val writer = DeltaWriter(source, scratch)
    if (shared != null) {
      scan(shared, targetFrom, targetTo, writer, alignment = sourceFrom - targetFrom)
    } else {
      algorithm.scanner(sourceSource, sourceFrom.toLong(), sourceTo.toLong()).use { scanner ->
        scan(scanner, targetFrom, targetTo, writer, alignment = sourceFrom - targetFrom)
      }
    }
    return Candidate(
      RegionNode.Delta(span.toLong(), writer.finishInstructions()),
      scratch.toByteArray()
    )
  }

  /**
   * Reads both sides as columns of differences and describes one against the other.
   *
   * The rearrangement is exact in both directions, so this risks nothing: it either packs smaller
   * than reading the bytes as they lie, or it is discarded like any other candidate.
   */
  private fun columnsCandidate(
    span: Int,
    sourceFrom: Int,
    sourceTo: Int,
    targetFrom: Int,
    targetTo: Int,
    widths: List<Int>
  ): Candidate? {
    if (!ColumnTransform.suits(widths, span) || sourceTo <= sourceFrom) return null
    val rearrangedSource = ColumnTransform.forward(source, sourceFrom, sourceTo, widths)
    val rearrangedTarget = ColumnTransform.forward(target, targetFrom, targetTo, widths)
    val scratch = ByteWriter(span.coerceIn(64, 1 shl 16))
    val writer = DeltaWriter(rearrangedSource, scratch)
    val asSource = rearrangedSource.asSource()
    algorithm.scanner(asSource).use { scanner ->
      scanner.scan(rearrangedTarget.asSource(), 0, rearrangedTarget.size.toLong(), writer, 0)
    }
    return Candidate(
      RegionNode.Columns(
        targetLength = span.toLong(),
        sourceFrom = sourceFrom.toLong(),
        sourceLength = (sourceTo - sourceFrom).toLong(),
        widths = widths,
        inner = RegionNode.Delta(span.toLong(), writer.finishInstructions())
      ),
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

  /**
   * One index per group of source children, spanning the first to the last of them.
   *
   * Built on first use and kept for as long as the composite is being encoded, because the whole
   * point is that several children search the same bytes: building it per child would index the
   * same megabytes again for each one.
   */
  private inner class Groups(sourceChildren: List<Child>, private val inherited: DeltaScanner?) {
    private val spans: Map<String, IntRange> = buildMap {
      for (child in sourceChildren) {
        val group = child.group ?: continue
        val seen = get(group)
        put(
          group,
          if (seen == null) child.from..child.to
          else minOf(seen.first, child.from)..maxOf(seen.last, child.to)
        )
      }
    }
    private val scanners = HashMap<String, DeltaScanner>()

    /**
     * The index a child should search, which is its own group's when it has one and its parent's
     * otherwise.
     *
     * Inheritance is what keeps decomposition and grouping from working against each other. A dex
     * inside an APK is searched against every dex; without this, taking that dex apart would
     * narrow each of its sections back down to the section of the same name, and the sections
     * would lose to the undecomposed form for no better reason than that.
     */
    fun scannerFor(group: String?): DeltaScanner? {
      if (group == null) return inherited
      val span = spans[group] ?: return inherited
      return scanners.getOrPut(group) {
        algorithm.scanner(sourceSource, span.first.toLong(), span.last.toLong())
      }
    }

    fun close() {
      for (scanner in scanners.values) scanner.close()
      scanners.clear()
    }
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
  private class Encoded(
    val node: RegionNode,
    val copy: CopyRun? = null,
    val children: List<RegionCost> = emptyList()
  )

  /** A node and what the regions inside it cost, kept together until one of them is chosen. */
  private class Built(val node: RegionNode, val costs: List<RegionCost>)

  private class CopyRun(val sourceFrom: Long, val length: Long)

  /**
   * One way of describing a region, and what it would add to the patch.
   *
   * Cost is measured on the packed content, because that is what reaches the patch. An unpacked
   * comparison systematically prefers whichever encoding emits fewer bytes over whichever emits
   * more compressible ones, and on real content those are rarely the same encoding.
   */
  private class Candidate(
    val node: RegionNode,
    val literals: ByteArray,
    val costs: List<RegionCost> = emptyList()
  ) {
    fun packedCost(): Long =
      node.structureBytes() + if (literals.isEmpty()) 0 else LzHuffman.compress(literals).size
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
