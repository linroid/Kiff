package com.linroid.kiff

import com.linroid.kiff.delta.DeltaScanner
import com.linroid.kiff.delta.MatchIndex
import com.linroid.kiff.delta.DeltaWriter

/**
 * General purpose binary delta: it treats both files as opaque byte streams and describes the
 * target as copies from anywhere in the source plus literal and run-length fill.
 *
 * Works on any pair of files. The archive-aware algorithms produce smaller patches for archives
 * because they can tell which regions are worth comparing.
 */
class BinaryDiff : DeltaPatchAlgorithm() {

  override val id: AlgorithmId = AlgorithmId.BINARY
  override val name: String = "binary"

  override fun encode(source: ByteArray, target: ByteArray, writer: DeltaWriter) {
    DeltaScanner(MatchIndex(source)).scan(target, 0, target.size, writer)
  }
}
