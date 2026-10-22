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
import com.linroid.kiff.format.height
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
 *
 * Positions are [Long], as formats give them. Both inputs are still held whole, because the search
 * indexes an array, so a position is narrowed to an index only where an array is read - and only
 * once the children it came from were found to tile a range of that array exactly.
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
   * [rootFormat] is applied without asking [ContainerFormat.detect], because the file as a whole
   * was already looked at more closely than a region is: a zip with a preamble should be taken
   * apart even though it does not begin with a local header. Detection is for regions found along
   * the way.
   */
  fun encode(
    rootFormat: ContainerFormat?,
    literals: ByteWriter,
    recorder: RegionRecorder?
  ): RegionNode {
    val children = rootFormat
      ?.let { partsOf(it, targetSource, 0, targetSource.size) }
      .orEmpty()
      .takeIf { it.size > 1 }
    // Both sides have to be the format, as they do for a nested region. Taken apart against a
    // source that could not be, the target's parts would have nothing to pair with, and each would
    // be stored or searched on its own - far larger than one search over the whole file.
    val sourceChildren = children?.let { partsOf(rootFormat!!, sourceSource, 0, sourceSource.size) }

    if (children == null || sourceChildren.isNullOrEmpty()) {
      // Nothing claims it, so it is one region and gets described as one.
      val literalsBefore = literals.size
      val node = leaf(
        WHOLE_FILE, RegionKind.WHOLE, 0, sourceSource.size, 0, targetSource.size, literals
      )
      recorder?.record(
        RegionCost(
          name = WHOLE_FILE,
          kind = RegionKind.WHOLE,
          encoding = node.encoding(),
          targetBytes = targetSource.size,
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
   *
   * [depth] is where the composite sits in the tree, counted as a reader counts it.
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
      val one = encodeChild(child, pairing.counterpartOf(child), groups, literals, depth + 1)
      encoded.add(one)
      costs.add(
        RegionCost(
          name = child.name,
          kind = child.kind,
          encoding = one.node.encoding(),
          targetBytes = child.size,
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
          RegionNode.Delta(child.size, writer.finishInstructions())
        }
      )
    }

    if (identical(counterpart.from, child.from, child.size, counterpart.size)) {
      return copyOf(counterpart.from, child.size)
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

    // The record is a composite of its parts, so its payload sits a level below it.
    val before = literals.size
    val payload = describe(child, counterpart, groups, literals, depth + 1)
    parts.add(payload.node)
    costs.add(
      RegionCost(
        // Distinguishable from the record around it, which is reported by its bare name.
        name = "${child.name} (data)",
        kind = child.kind,
        encoding = payload.node.encoding(),
        targetBytes = child.contentTo - child.contentFrom,
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
   *
   * [depth] is where the payload sits in the tree. Nothing built here reaches deeper than a reader
   * goes: taking the payload apart is only tried while its children would still fit, and kept only
   * if everything it built does.
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
      depth + 1 <= MAX_REGION_DEPTH
    ) {
      decompose(sourceFrom, sourceTo, targetFrom, targetTo, depth, groups.scannerFor(child.group))
        ?.takeIf { depth + it.node.height() <= MAX_REGION_DEPTH }
    } else {
      null
    }

    // A columns region's node sits a level below it, so it needs room for one more.
    val columns = child.columns?.takeIf { depth < MAX_REGION_DEPTH }
    val flatScratch = scratchFor(targetTo - targetFrom)
    val flat = leaf(
      name, kind, sourceFrom, sourceTo, targetFrom, targetTo, flatScratch,
      groups.scannerFor(child.group), columns
    )
    val flatBytes = flatScratch.toByteArray()

    if (decomposed != null && decomposed.cost() < Candidate(flat, flatBytes).cost()) {
      literals.writeBytes(decomposed.literals)
      return Built(decomposed.node, decomposed.costs)
    }
    literals.writeBytes(flatBytes)
    return Built(flat, emptyList())
  }

  /** Builds the decomposed form of a range into its own buffer, so it can be weighed and dropped. */
  private fun decompose(
    sourceFrom: Long,
    sourceTo: Long,
    targetFrom: Long,
    targetTo: Long,
    depth: Int,
    inherited: DeltaScanner?
  ): Candidate? {
    val format = registry.formatFor(targetSource, targetFrom, targetTo) ?: return null
    val targetChildren = partsOf(format, targetSource, targetFrom, targetTo)
    if (targetChildren.size <= 1) return null
    val sourceChildren = partsOf(format, sourceSource, sourceFrom, sourceTo)
    if (sourceChildren.isEmpty()) return null

    val scratch = scratchFor(targetTo - targetFrom)
    val built = composite(format, sourceChildren, targetChildren, scratch, depth, inherited)
    return Candidate(built.node, scratch.toByteArray(), built.costs)
  }

  /**
   * What [format] makes of `[from, to)` of [input], or nothing when its children do not tile that
   * range exactly. Such a decomposition could not be restored from, and its positions cannot be
   * trusted as indexes either, so the range is described as if the format had not claimed it.
   */
  private fun partsOf(
    format: ContainerFormat,
    input: ByteArraySource,
    from: Long,
    to: Long
  ): List<Child> {
    val children = format.decompose(input, from, to)
    return if (ContainerFormatTiling.tiles(children, from, to)) children else emptyList()
  }

  private fun addPart(
    into: MutableList<RegionNode>,
    costs: MutableList<RegionCost>,
    name: String,
    kind: RegionKind,
    sourceFrom: Long,
    sourceTo: Long,
    targetFrom: Long,
    targetTo: Long,
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
        targetBytes = targetTo - targetFrom,
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
    sourceFrom: Long,
    sourceTo: Long,
    targetFrom: Long,
    targetTo: Long,
    literals: ByteWriter,
    shared: DeltaScanner? = null,
    columns: List<Int>? = null
  ): RegionNode {
    val span = targetTo - targetFrom
    if (identical(sourceFrom, targetFrom, span, sourceTo - sourceFrom)) {
      return copyNode(sourceFrom, span)
    }
    val info = RegionInfo(
      name = name,
      kind = kind,
      targetBytes = span,
      sourceBytes = sourceTo - sourceFrom,
      looksLikeText = looksLikeText(source, sourceFrom, sourceTo) &&
        looksLikeText(target, targetFrom, targetTo)
    )
    val plan = planner.plan(info)
    if (plan == RegionAlgorithm.RAW) return raw(targetFrom, targetTo, literals)

    var best = binaryCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo, shared)
    if (plan == RegionAlgorithm.TEXT) {
      val text = textCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo)
      if (text != null && text.cost() < best.cost()) best = text
    }
    // A table only its format could recognise gets one more way of being read.
    if (columns != null) {
      val rearranged = columnsCandidate(span, sourceFrom, sourceTo, targetFrom, targetTo, columns)
      if (rearranged != null && rearranged.cost() < best.cost()) best = rearranged
    }

    if (!beatsStoringTarget(best.node.structureBytes(), best.literals, targetFrom, targetTo)) {
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
    span: Long,
    sourceFrom: Long,
    sourceTo: Long,
    targetFrom: Long,
    targetTo: Long,
    shared: DeltaScanner?
  ): Candidate {
    val scratch = scratchFor(span)
    val writer = DeltaWriter(source, scratch)
    if (shared != null) {
      scan(shared, targetFrom, targetTo, writer, alignment = sourceFrom - targetFrom)
    } else {
      algorithm.scanner(sourceSource, sourceFrom, sourceTo).use { scanner ->
        scan(scanner, targetFrom, targetTo, writer, alignment = sourceFrom - targetFrom)
      }
    }
    return Candidate(RegionNode.Delta(span, writer.finishInstructions()), scratch.toByteArray())
  }

  /**
   * Reads both sides as columns of differences and describes one against the other.
   *
   * The rearrangement is exact in both directions, so this risks nothing: it either packs smaller
   * than reading the bytes as they lie, or it is discarded like any other candidate.
   */
  private fun columnsCandidate(
    span: Long,
    sourceFrom: Long,
    sourceTo: Long,
    targetFrom: Long,
    targetTo: Long,
    widths: List<Int>
  ): Candidate? {
    if (!ColumnTransform.suits(widths, span.toInt()) || sourceTo <= sourceFrom) return null
    val rearrangedSource =
      ColumnTransform.forward(source, sourceFrom.toInt(), sourceTo.toInt(), widths)
    val rearrangedTarget =
      ColumnTransform.forward(target, targetFrom.toInt(), targetTo.toInt(), widths)
    val scratch = scratchFor(span)
    val writer = DeltaWriter(rearrangedSource, scratch)
    val asSource = rearrangedSource.asSource()
    algorithm.scanner(asSource).use { scanner ->
      scanner.scan(rearrangedTarget.asSource(), 0, rearrangedTarget.size.toLong(), writer, 0)
    }
    return Candidate(
      RegionNode.Columns(
        targetLength = span,
        sourceFrom = sourceFrom,
        sourceLength = sourceTo - sourceFrom,
        widths = widths,
        inner = RegionNode.Delta(span, writer.finishInstructions())
      ),
      scratch.toByteArray()
    )
  }

  private fun textCandidate(
    span: Long,
    sourceFrom: Long,
    sourceTo: Long,
    targetFrom: Long,
    targetTo: Long
  ): Candidate? {
    val scratch = scratchFor(span)
    val edits = TextRegion.encode(
      lineAlgorithm,
      source, sourceFrom.toInt(), sourceTo.toInt(),
      target, targetFrom.toInt(), targetTo.toInt(),
      scratch
    ) ?: return null
    return Candidate(
      RegionNode.Text(
        targetLength = span,
        sourceFrom = sourceFrom,
        sourceLength = sourceTo - sourceFrom,
        edits = edits
      ),
      scratch.toByteArray()
    )
  }

  private inline fun keepIfItBeatsStoring(
    targetFrom: Long,
    targetTo: Long,
    literals: ByteWriter,
    encode: (ByteWriter) -> RegionNode
  ): RegionNode {
    val scratch = scratchFor(targetTo - targetFrom)
    val node = encode(scratch)
    val candidate = scratch.toByteArray()
    if (!beatsStoringTarget(node.structureBytes(), candidate, targetFrom, targetTo)) {
      return raw(targetFrom, targetTo, literals)
    }
    literals.writeBytes(candidate)
    return node
  }

  private fun beatsStoringTarget(structure: Long, candidate: ByteArray, from: Long, to: Long) =
    beatsStoring(structure, candidate, target, from.toInt(), to.toInt())

  private fun raw(from: Long, to: Long, literals: ByteWriter): RegionNode {
    literals.writeBytes(target, from.toInt(), to.toInt())
    return RegionNode.Raw(to - from)
  }

  /** A buffer for describing [span] bytes, sized for the common case rather than the worst. */
  private fun scratchFor(span: Long) = ByteWriter(span.coerceIn(64, 1L shl 16).toInt())

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
    targetFrom: Long,
    targetTo: Long,
    writer: DeltaWriter,
    alignment: Long
  ) {
    scanner.scan(targetSource, targetFrom, targetTo, writer, alignment)
  }

  private fun identical(
    sourceFrom: Long,
    targetFrom: Long,
    length: Long,
    sourceLength: Long
  ): Boolean {
    if (length != sourceLength) return false
    if (sourceFrom < 0 || sourceFrom + length > source.size) return false
    if (targetFrom + length > target.size) return false
    val sourceAt = sourceFrom.toInt()
    val targetAt = targetFrom.toInt()
    for (i in 0 until length.toInt()) {
      if (source[sourceAt + i] != target[targetAt + i]) return false
    }
    return true
  }

  /**
   * One index per group of source children, spanning the payload of the first to that of the last.
   *
   * Built on first use and kept for as long as the composite is being encoded, because the whole
   * point is that several children search the same bytes: building it per child would index the
   * same megabytes again for each one.
   *
   * The span starts at a payload, not at the record around it, because the index samples a block
   * every few bytes counted from where it starts. A payload lays its content out from its own first
   * byte - a dex keeps its code on a four-byte grid - while a zip local header can sit at any
   * offset. Counted from the header, every block would fall off that grid, and an exact match only
   * just long enough to be found would be missed whenever it starts on it. That is often all a
   * rebuilt method has in common with its old self, and what the search realigns on after it: on
   * real R8 builds, missing those matches made a dex's code pack 2-3% larger searched with its
   * siblings than searched alone.
   */
  private inner class Groups(sourceChildren: List<Child>, private val inherited: DeltaScanner?) {
    private val spans: Map<String, LongRange> = buildMap {
      for (child in sourceChildren) {
        val group = child.group ?: continue
        val seen = get(group)
        put(
          group,
          if (seen == null) child.contentFrom..child.contentTo
          else minOf(seen.first, child.contentFrom)..maxOf(seen.last, child.contentTo)
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
        algorithm.scanner(sourceSource, span.first, span.last)
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

    /** The source children as a set, for checking what [ContainerFormat.pairUnmatched] answers. */
    private val offered by lazy { source.toHashSet() }

    fun counterpartOf(child: Child): Child? =
      byName[child.name]
        ?: child.contentKey?.let { byContent[it] }
        ?: format.pairUnmatched(child, source)?.takeIf { it in offered }
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
   *
   * What content costs packed depends on the rest of the patch, which is not written yet. A packed
   * stream opens with about 165 bytes of code lengths however little it holds, and a patch stores
   * its content instead where packing would not make it smaller. So where the rest of the content
   * is packed anyway, a candidate's adds only its share of the stream; where it is the only
   * content, it costs itself packed or stored, whichever is smaller. A candidate is charged both,
   * summed: between two, that keeps the one that loses less in whichever case it is wrong about.
   *
   * Either case alone was measured wrong. Charged as if packed anyway, a dex whose id table only
   * moved tied with being described whole, though taken apart it left two bytes of content, and
   * a patch that small stores them: 182 bytes against 303. Charged as the only content, an archive
   * inside an archive was taken apart for 162 bytes of content counted as stored, against 208 for
   * the 247 bytes it left whole packed; but the rest of the patch packed too, and the patch came
   * out at 365 bytes against 334.
   *
   * A description weighed against storing its bytes, by [beatsStoring], is still charged as if
   * packed anyway. Charged both there, small regions of three patches between real release APKs
   * were stored rather than described, and each patch grew by 26 to 64 bytes.
   */
  private class Candidate(
    val node: RegionNode,
    val literals: ByteArray,
    val costs: List<RegionCost> = emptyList()
  ) {
    fun cost(): Long {
      val packed = LzHuffman.estimate(literals)
      val share = maxOf(0, packed - LzHuffman.tableBytes)
      val alone = minOf(literals.size, packed)
      return 2 * node.structureBytes() + share + alone
    }
  }

  private companion object {
    const val WHOLE_FILE = "(whole file)"
    const val MIN_SEARCHABLE = 64 * 1024

    /** How far into a region the NUL check looks before it accepts the bytes as text. */
    const val TEXT_SNIFF = 8 * 1024

    fun looksLikeText(bytes: ByteArray, from: Long, to: Long): Boolean {
      if (to <= from) return false
      val end = minOf(to, from + TEXT_SNIFF).toInt()
      for (i in from.toInt() until end) {
        if (bytes[i] == 0.toByte()) return false
      }
      return true
    }
  }
}
