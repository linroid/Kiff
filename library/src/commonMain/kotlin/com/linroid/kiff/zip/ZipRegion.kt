package com.linroid.kiff.zip

/**
 * A zip archive as a gap-free sequence of byte ranges. Describing every region, including the ones
 * the format does not name, is what lets a patch rebuild the archive byte for byte.
 */
internal sealed class ZipRegion {
  abstract val from: Int
  abstract val to: Int

  val size: Int get() = to - from

  /** A local file header plus its data. */
  class Record(val entry: ZipEntry) : ZipRegion() {
    override val from: Int get() = entry.localHeaderOffset
    override val to: Int get() = entry.recordEnd
  }

  /**
   * Bytes that belong to no entry: a preamble, alignment padding, or - in an APK - the signing
   * block that sits between the last entry and the central directory.
   */
  class Gap(override val from: Int, override val to: Int, val key: ZipGapKey) : ZipRegion()

  /** Central directory, end-of-central-directory record and any archive comment. */
  class Directory(override val from: Int, override val to: Int) : ZipRegion()
}

/** Where a gap sits in the archive, so the same gap can be found in the other archive. */
internal data class ZipGapKey(val afterEntry: String?, val beforeDirectory: Boolean) {
  companion object {
    val PREAMBLE = ZipGapKey(afterEntry = null, beforeDirectory = false)
    val BEFORE_DIRECTORY = ZipGapKey(afterEntry = null, beforeDirectory = true)
  }
}
