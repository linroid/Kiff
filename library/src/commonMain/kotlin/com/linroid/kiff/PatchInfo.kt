package com.linroid.kiff

data class PatchInfo(
  val patcher: PatcherId,
  val formatVersion: Int,
  val sourceSize: Long,
  val sourceCrc32: UInt,
  val targetSize: Long,
  val targetCrc32: UInt,
  val patchSize: Long
) {
  /** Patch size as a fraction of the target size; smaller is better. */
  val ratio: Double
    get() = if (targetSize == 0L) 0.0 else patchSize.toDouble() / targetSize.toDouble()
}
