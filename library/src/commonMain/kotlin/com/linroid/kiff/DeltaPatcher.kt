package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.Crc32
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.format.PatchPayload
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.format.toHex
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.io.materialize
import com.linroid.kiff.region.RegionCost
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.region.RegionReport

/**
 * Base class for the bundled patchers. They differ only in how they *describe* the target - the
 * container, the checksum verification and the restore path are shared, so any patch restores the
 * target byte for byte no matter which patcher produced it.
 *
 * Checksums are computed straight off the [SeekableSource], a chunk at a time. The search still
 * indexes a [ByteArray], so both inputs are materialized once here and every scanner built during
 * the encode reuses those arrays.
 */
sealed class DeltaPatcher : Patcher {

  /**
   * The delta algorithm this patcher drives. Patchers differ in how they carve the inputs into
   * regions, not in how bytes are searched, so any algorithm works with any of them.
   */
  abstract val algorithm: DeltaAlgorithm

  final override fun createPatch(source: SeekableSource, target: SeekableSource): ByteArray =
    build(source, target, recorder = null)

  /**
   * [createPatch], additionally telling [recorder] what each region of the target cost.
   *
   * Recording rides along with the ordinary encode instead of replacing it, so a measured patch is
   * byte for byte the patch [createPatch] would have produced.
   */
  internal fun build(
    source: SeekableSource,
    target: SeekableSource,
    recorder: RegionRecorder?
  ): ByteArray {
    val sourceCrc = Crc32.compute(source)
    val targetCrc = Crc32.compute(target)
    val sourceBytes = source.materialize("Source")
    val targetBytes = target.materialize("Target")

    val literals = ByteWriter((target.size / 8).coerceIn(64, 1L shl 20).toInt())
    val root = encode(sourceBytes, targetBytes, literals, recorder)
    check(root.targetLength == target.size) {
      "$name described ${root.targetLength} bytes but the target has ${target.size}"
    }
    val payload = PatchPayload.write(root, literals.toByteArray())
    val out = ByteWriter(payload.size + HEADER_ESTIMATE)
    PatchFormat.writeHeader(out, id, source.size, sourceCrc, target.size, targetCrc)
    out.writeBytes(payload)
    return out.toByteArray()
  }

  final override fun applyPatch(source: SeekableSource, patch: ByteArray): ByteArray {
    val reader = ByteReader(patch)
    val header = PatchFormat.readHeader(reader)
    if (header.patcher != id) {
      throw KiffException.InvalidPatch(
        "Patch was built by ${header.patcher.name.lowercase()}, not $name"
      )
    }
    if (source.size != header.sourceSize) {
      throw KiffException.SourceMismatch(
        "Source is ${source.size} bytes, the patch expects ${header.sourceSize}"
      )
    }
    val sourceCrc = Crc32.compute(source)
    if (sourceCrc != header.sourceCrc32) {
      throw KiffException.SourceMismatch(
        "Source CRC-32 ${sourceCrc.toHex()} does not match ${header.sourceCrc32.toHex()}"
      )
    }
    val bytes = source.materialize("Source").bytes
    val target = PatchPayload.read(bytes, patch, reader.offset, header.targetSize)
    val targetCrc = Crc32.compute(target)
    if (targetCrc != header.targetCrc32) {
      throw KiffException.VerificationFailed(
        "Restored target CRC-32 ${targetCrc.toHex()} does not match ${header.targetCrc32.toHex()}"
      )
    }
    return target
  }

  final override fun createPatch(source: ByteArray, target: ByteArray): ByteArray =
    createPatch(source.asSource(), target.asSource())

  final override fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray =
    applyPatch(source.asSource(), patch)

  /**
   * Builds a patch and reports where its bytes went, region by region.
   *
   * Every patcher carves its target into regions - a zip into entries, gaps and its directory, this
   * one into a single whole - so every patcher can say what each of them cost. Unlike the `analyze`
   * reports this has to do the full encode, because a region's cost is only known once it has been
   * described; the patch itself is discarded.
   */
  fun explain(source: ByteArray, target: ByteArray): RegionReport {
    val regions = ArrayList<RegionCost>()
    val recorder = RegionRecorder { cost -> regions.add(cost) }
    val patch = build(source.asSource(), target.asSource(), recorder)
    return RegionReport(
      regions = regions,
      targetSize = target.size.toLong(),
      patchSize = patch.size.toLong()
    )
  }

  /**
   * Describes [target] in terms of [source] as a region tree, appending whatever content the tree's
   * leaves carry to the shared [literals] stream and reporting each region's cost to [recorder].
   */
  internal abstract fun encode(
    source: ByteArraySource,
    target: ByteArraySource,
    literals: ByteWriter,
    recorder: RegionRecorder?
  ): RegionNode

  private companion object {
    const val HEADER_ESTIMATE = 32
  }
}
