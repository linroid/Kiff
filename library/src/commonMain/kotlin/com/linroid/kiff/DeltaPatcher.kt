package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaReader
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.internal.ByteWriter
import com.linroid.kiff.internal.Crc32
import com.linroid.kiff.internal.materialize
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource

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

  final override fun createPatch(source: SeekableSource, target: SeekableSource): ByteArray {
    val sourceCrc = Crc32.compute(source)
    val targetCrc = Crc32.compute(target)
    val sourceBytes = source.materialize("Source")
    val targetBytes = target.materialize("Target")

    val writer = DeltaWriter(sourceBytes.bytes, target.size)
    encode(sourceBytes, targetBytes, writer)
    check(writer.length == target.size) {
      "$name described ${writer.length} bytes but the target has ${target.size}"
    }
    val delta = writer.finish()
    val out = ByteWriter(delta.size + HEADER_ESTIMATE)
    PatchFormat.writeHeader(out, id, source.size, sourceCrc, target.size, targetCrc)
    out.writeBytes(delta)
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
    val target = DeltaReader.apply(bytes, patch, reader.offset, header.targetSize)
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

  /** Describes [target] in terms of [source] by driving [sink]. */
  internal abstract fun encode(
    source: ByteArraySource,
    target: ByteArraySource,
    sink: DeltaWriter
  )

  private companion object {
    const val HEADER_ESTIMATE = 32
  }
}

internal fun UInt.toHex(): String = toString(16).padStart(8, '0')
