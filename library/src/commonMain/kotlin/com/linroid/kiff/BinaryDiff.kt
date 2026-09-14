package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.DeltaWriter
import com.linroid.kiff.delta.RollingHashAlgorithm

/**
 * General purpose binary patcher: it treats both files as opaque byte streams and describes the
 * target as copies from anywhere in the source plus literal and run-length fill.
 *
 * Works on any pair of files. The archive-aware patchers produce smaller patches for archives
 * because they can tell which regions are worth comparing.
 */
class BinaryDiff internal constructor(
  internal override val algorithm: DeltaAlgorithm
) : DeltaPatcher() {

  constructor() : this(RollingHashAlgorithm)

  override val id: PatcherId = PatcherId.BINARY
  override val name: String = "binary"

  override fun encode(source: ByteArray, target: ByteArray, writer: DeltaWriter) {
    algorithm.scanner(source).scan(target, 0, target.size, writer)
  }
}
