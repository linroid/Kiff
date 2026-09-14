package com.linroid.kiff

data class PatchInfo(
  val algorithm: AlgorithmId,
  val formatVersion: Int,
  val sourceSize: Int,
  val sourceCrc32: UInt,
  val targetSize: Int,
  val targetCrc32: UInt,
  val patchSize: Int
) {
  /** Patch size as a fraction of the target size; smaller is better. */
  val ratio: Double
    get() = if (targetSize == 0) 0.0 else patchSize.toDouble() / targetSize.toDouble()
}
