package com.linroid.kiff.delta

import com.linroid.kiff.internal.materialize
import com.linroid.kiff.internal.toIntIndex
import com.linroid.kiff.io.SeekableSource

/**
 * The algorithm Kiff ships: a rolling hash over [RollingHash.WINDOW]-byte blocks of the source,
 * sampled every 4-16 bytes depending on how large the indexed range is, which keeps the index small
 * enough to diff APK-sized files in a couple of seconds.
 *
 * The search itself works over a [ByteArray], so [scanner] materializes the range it indexes. A
 * patcher materializes once before encoding and every scanner after that reuses the same array, so
 * a file is read one time rather than per region.
 */
object RollingHashAlgorithm : DeltaAlgorithm {

  override val name: String = "rolling-hash"

  override fun scanner(source: SeekableSource, from: Long, to: Long): DeltaScanner =
    RollingHashScanner(
      MatchIndex(
        source.materialize("Source").bytes,
        from.toIntIndex("Source offset"),
        to.toIntIndex("Source offset")
      )
    )
}
