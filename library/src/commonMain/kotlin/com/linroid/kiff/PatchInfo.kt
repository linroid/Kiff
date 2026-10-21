package com.linroid.kiff

/** What a patch's header says, and how large the patch is. */
data class PatchInfo(
  /**
   * What the writer took the target to be: plain bytes, a zip or an APK.
   *
   * Informational only. Every patch is applied the same way whatever this says, and a format
   * added through [KiffOptions.containers] records [PatcherId.BINARY].
   */
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
