package com.linroid.kiff.delta

/**
 * The default algorithm: a rolling hash over [RollingHash.WINDOW]-byte blocks of the source,
 * sampled every 4-16 bytes depending on how large the indexed range is, which keeps the index small
 * enough to diff APK-sized files in a couple of seconds.
 */
internal object RollingHashAlgorithm : DeltaAlgorithm {

  override val name: String = "rolling-hash"

  override fun scanner(source: ByteArray, from: Int, to: Int): DeltaScanner =
    RollingHashScanner(MatchIndex(source, from, to))
}
