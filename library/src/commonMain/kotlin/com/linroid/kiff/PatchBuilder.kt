package com.linroid.kiff

import com.linroid.kiff.container.ContainerEncoder
import com.linroid.kiff.container.ContainerFormat
import com.linroid.kiff.delta.encodeWhole
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.Crc32
import com.linroid.kiff.format.MAX_REGION_DEPTH
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.format.PatchPayload
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.format.encoding
import com.linroid.kiff.format.height
import com.linroid.kiff.format.structureBytes
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.materialize
import com.linroid.kiff.io.requireIndexable
import com.linroid.kiff.region.RegionCost
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.region.WHOLE_FILE_REGION

/**
 * Builds patches: reads both inputs, decides what the target is as a whole, describes it, and
 * writes the header.
 *
 * The search indexes a [ByteArray], so both inputs are read into memory once and every scanner
 * built during the encode reuses those arrays; their checksums are taken from them too.
 */
internal object PatchBuilder {

  /**
   * Returns a patch that rebuilds [target] from [source], telling [recorder] what each region of
   * the target cost.
   *
   * Recording rides along with the ordinary encode instead of replacing it, so a measured patch is
   * byte for byte the patch an unmeasured one would have been.
   *
   * [structure] overrides detection, so a test can describe a target as another kind of file.
   */
  fun build(
    source: SeekableSource,
    target: SeekableSource,
    options: KiffOptions,
    recorder: RegionRecorder? = null,
    structure: Structure? = null
  ): ByteArray {
    // Both sizes are checked before either input is read, so one too large costs nothing to refuse.
    source.requireIndexable("Source")
    target.requireIndexable("Target")
    // Each input is read once, and its checksum taken from the very bytes the patch describes: two
    // reads cost twice the I/O, and a file that changed between them gave a header for one version
    // and a tree for the other, a patch that every apply refuses.
    val sourceBytes = source.materialize("Source")
    val targetBytes = target.materialize("Target")
    val sourceCrc = Crc32.compute(sourceBytes.bytes)
    val targetCrc = Crc32.compute(targetBytes.bytes)

    val chosen = structure ?: Structure.detect(targetBytes, options.containers)
    val inputs = Inputs(sourceBytes, targetBytes, options, chosen.name)
    val payload = when (chosen) {
      Structure.Bytes -> inputs.payload(recorder, inputs::whole)
      is Structure.Archive -> inputs.payload(recorder) { literals, costs ->
        inputs.parts(chosen.format, literals, costs)
      }
      is Structure.Weighed -> inputs.weighed(chosen.format, recorder)
    }

    val flags = if (payload.treePacked) FLAGS or PatchFormat.FLAG_PACKED_TREE else FLAGS
    val out = ByteWriter(payload.bytes.size + HEADER_ESTIMATE)
    PatchFormat.writeHeader(
      out, chosen.id, sourceBytes.size, sourceCrc, targetBytes.size, targetCrc, flags
    )
    out.writeBytes(payload.bytes)
    return out.toByteArray()
  }

  private class Inputs(
    val source: ByteArraySource,
    val target: ByteArraySource,
    val options: KiffOptions,
    val name: String
  ) {

    /** The target as one region, searched for in the whole source. */
    fun whole(literals: ByteWriter, recorder: RegionRecorder?): RegionNode {
      val literalsBefore = literals.size
      val node = encodeWhole(source, target, options.algorithm, literals)
      recorder?.record(
        RegionCost(
          name = WHOLE_FILE_REGION,
          kind = RegionKind.WHOLE,
          encoding = node.encoding(),
          targetBytes = target.size,
          instructionBytes = node.structureBytes(),
          literalBytes = (literals.size - literalsBefore).toLong()
        )
      )
      return node
    }

    /** The target taken apart by [format], each part described against its counterpart. */
    fun parts(
      format: ContainerFormat,
      literals: ByteWriter,
      recorder: RegionRecorder?
    ): RegionNode = ContainerEncoder(
      source, target, options.algorithm, options.planner, options.lineAlgorithm, options.containers
    ).encode(format, literals, recorder)

    /**
     * Describes the target both whole and taken apart by [format], and keeps the smaller patch.
     *
     * A tie goes to the whole file, which is the patch Kiff 0.1.0 would have built.
     */
    fun weighed(format: ContainerFormat, recorder: RegionRecorder?): PatchPayload.Written {
      val wholeCosts = ArrayList<RegionCost>()
      val asWhole = payload(RegionRecorder(wholeCosts::add), ::whole)
      val partsCosts = ArrayList<RegionCost>()
      val asParts = payload(RegionRecorder(partsCosts::add)) { literals, costs ->
        parts(format, literals, costs)
      }
      val keepParts = asParts.bytes.size < asWhole.bytes.size
      val costs = if (keepParts) partsCosts else wholeCosts
      if (recorder != null) for (cost in costs) recorder.record(cost)
      return if (keepParts) asParts else asWhole
    }

    /** Runs [encode] and writes what it described as a payload: tree, then content. */
    inline fun payload(
      recorder: RegionRecorder?,
      encode: (ByteWriter, RegionRecorder?) -> RegionNode
    ): PatchPayload.Written {
      val literals = ByteWriter((target.size / 8).coerceIn(64, 1L shl 20).toInt())
      val root = encode(literals, recorder)
      check(root.targetLength == target.size) {
        "The $name encoding described ${root.targetLength} bytes but the target has ${target.size}"
      }
      // A tree deeper than readers accept would make a patch nobody can apply, which is worse than
      // no patch: refused here, the failure points at the encoder rather than at every device.
      check(root.height() <= MAX_REGION_DEPTH) {
        "The $name encoding built a region tree ${root.height()} deep; " +
          "readers accept $MAX_REGION_DEPTH"
      }
      return PatchPayload.write(root, literals.toByteArray(), target.bytes.takeIf { CHECKSUMMED })
    }
  }

  // One decision, used twice: the flag in the header and the checksums in the tree have to agree,
  // or a reader either misses them or reads past them.
  private const val FLAGS = PatchFormat.FLAG_REGION_CHECKSUMS
  private const val CHECKSUMMED = FLAGS and PatchFormat.FLAG_REGION_CHECKSUMS != 0

  private const val HEADER_ESTIMATE = 32
}
