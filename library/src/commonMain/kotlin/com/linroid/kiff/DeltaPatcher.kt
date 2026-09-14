package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaReader
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.internal.ByteWriter
import com.linroid.kiff.internal.Crc32

/**
 * Base class for the bundled patchers. They differ only in how they *describe* the target - the
 * container, the checksum verification and the restore path are shared, so any patch restores the
 * target byte for byte no matter which patcher produced it.
 */
sealed class DeltaPatcher : Patcher {

  final override fun createPatch(source: ByteArray, target: ByteArray): ByteArray {
    val writer = DeltaWriter(target.size)
    encode(source, target, writer)
    check(writer.length == target.size) {
      "$name described ${writer.length} bytes but the target has ${target.size}"
    }
    val delta = writer.finish()
    val out = ByteWriter(delta.size + HEADER_ESTIMATE)
    PatchFormat.writeHeader(out, id, source, target)
    out.writeBytes(delta)
    return out.toByteArray()
  }

  final override fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray {
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
    val target = DeltaReader.apply(source, patch, reader.offset, header.targetSize)
    val targetCrc = Crc32.compute(target)
    if (targetCrc != header.targetCrc32) {
      throw KiffException.VerificationFailed(
        "Restored target CRC-32 ${targetCrc.toHex()} does not match ${header.targetCrc32.toHex()}"
      )
    }
    return target
  }

  /**
   * The delta algorithm this patcher drives. Patchers differ in how they carve the inputs into
   * regions, not in how bytes are searched, so any algorithm works with any of them.
   */
  internal abstract val algorithm: DeltaAlgorithm

  /** Describes [target] in terms of [source] by driving [writer]. */
  internal abstract fun encode(source: ByteArray, target: ByteArray, writer: DeltaWriter)

  private companion object {
    const val HEADER_ESTIMATE = 32
  }
}

internal fun UInt.toHex(): String = toString(16).padStart(8, '0')
