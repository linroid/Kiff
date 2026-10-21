package com.linroid.kiff

import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.Crc32
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.format.PatchPayload
import com.linroid.kiff.format.toHex
import com.linroid.kiff.io.ByteArrayRestoreTarget
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.toIntIndex

/**
 * Applies patches, every one the same way: what the target was taken to be when the patch was
 * built is recorded in its tree, so nothing here needs to know it.
 *
 * Applying reads the source a chunk at a time and never holds it.
 */
internal object PatchRestorer {

  fun apply(source: SeekableSource, patch: ByteArray): ByteArray {
    val reader = ByteReader(patch)
    val header = checkedHeader(source, reader)
    val collected = ByteArrayRestoreTarget(header.targetSize.toIntIndex("Target size"))
    restore(source, patch, header, reader.offset, collected)
    return collected.toByteArray()
  }

  /**
   * Restores the target into [target] instead of returning it.
   *
   * A patch describes its target front to back, so nothing here ever holds the result - and the
   * source is addressed rather than read in. That is the difference between a restore a build
   * server can do and one a phone can: applying a patch for a seventy-megabyte package needs tens
   * of megabytes this way, against something over a hundred and seventy when both files are held.
   */
  fun apply(source: SeekableSource, patch: ByteArray, target: RestoreTarget) {
    val reader = ByteReader(patch)
    val header = checkedHeader(source, reader)
    restore(source, patch, header, reader.offset, target)
  }

  /** Reads the header and refuses a source that is not the one the patch was built against. */
  private fun checkedHeader(source: SeekableSource, reader: ByteReader): PatchFormat.Header {
    // The patcher byte says what the writer took the target to be, not how to read it.
    val header = PatchFormat.readHeader(reader)
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
    return header
  }

  private fun restore(
    source: SeekableSource,
    patch: ByteArray,
    header: PatchFormat.Header,
    payloadFrom: Int,
    target: RestoreTarget
  ) {
    val targetCrc = PatchPayload.apply(
      source = source,
      patch = patch,
      from = payloadFrom,
      targetSize = header.targetSize,
      checksums = header.flags and PatchFormat.FLAG_REGION_CHECKSUMS != 0,
      out = target
    )
    if (targetCrc != header.targetCrc32) {
      throw KiffException.VerificationFailed(
        "Restored target CRC-32 ${targetCrc.toHex()} does not match ${header.targetCrc32.toHex()}"
      )
    }
  }
}
