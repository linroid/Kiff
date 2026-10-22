package com.linroid.kiff

/** What a patch's header says, and how large the patch is. */
public data class PatchInfo(
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
  val patchSize: Long,
  /**
   * Whether the patch's region tree is packed, which a writer does only where that makes the patch
   * smaller: by a fifth between large text files, and by a percent or so between APKs.
   *
   * Kiff 0.1 and 0.2 refuse a patch that says so; every later release reads it.
   */
  val treePacked: Boolean
) {
  /** Patch size as a fraction of the target size; smaller is better. */
  val ratio: Double
    get() = if (targetSize == 0L) 0.0 else patchSize.toDouble() / targetSize.toDouble()
}
