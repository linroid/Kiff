package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.delta.RollingHashAlgorithm
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.region.RegionRecorder
import com.linroid.kiff.region.WHOLE_FILE_REGION

/**
 * General purpose binary patcher: it treats both files as opaque byte streams and describes the
 * target as copies from anywhere in the source plus literal and run-length fill.
 *
 * Works on any pair of files. The archive-aware patchers produce smaller patches for archives
 * because they can tell which regions are worth comparing.
 */
class BinaryPatcher(
  override val algorithm: DeltaAlgorithm = RollingHashAlgorithm
) : DeltaPatcher() {

  override val id: PatcherId = PatcherId.BINARY
  override val name: String = "binary"

  override fun encode(
    source: ByteArraySource,
    target: ByteArraySource,
    sink: DeltaWriter,
    recorder: RegionRecorder?
  ) {
    val instructionsBefore = sink.instructionBytes
    val literalsBefore = sink.literalBytes
    algorithm.scanner(source).scan(target, 0, target.size, sink)
    // There is only ever one region here: this patcher does not carve the target up at all.
    recorder?.record(
      WHOLE_FILE_REGION,
      RegionKind.WHOLE,
      target.size,
      sink.instructionBytes - instructionsBefore,
      sink.literalBytes - literalsBefore
    )
  }
}
