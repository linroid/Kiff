package com.linroid.kiff.apk

import com.linroid.kiff.region.RegionReport

/** What the entries of one kind cost in a patch. */
data class ApkKindCost(
  val kind: ApkEntryKind,
  /** Entries of this kind in the target archive. */
  val entryCount: Int,
  /** Bytes of the target these entries cover. */
  val targetBytes: Long,
  /** Bytes of instruction stream spent describing them. */
  val instructionBytes: Long,
  /** Content they contributed that exists nowhere in the source. */
  val literalBytes: Long
) {
  /** Everything entries of this kind added to the delta. */
  val patchBytes: Long get() = instructionBytes + literalBytes

  /** Patch bytes per target byte; near 0.0 when the patch merely points at these entries. */
  val ratio: Double get() = if (targetBytes == 0L) 0.0 else patchBytes.toDouble() / targetBytes
}

/**
 * Where a patch's bytes went, grouped the way an APK is actually built.
 *
 * This is the question [ApkDiffReport] cannot answer: that one says what *changed*, this one says
 * what the change cost, which is not the same thing - a large entry can change and still be nearly
 * free, and a small one can be expensive.
 */
data class ApkPatchReport(
  /** The underlying region-by-region attribution. */
  val regions: RegionReport,
  /** Entry kinds, most expensive first. */
  val kinds: List<ApkKindCost>,
  /** Bytes spent on regions that belong to no entry, the signing block above all. */
  val gapBytes: Long,
  /** Bytes spent on the central directory and the end-of-central-directory record. */
  val directoryBytes: Long
) {
  val targetSize: Long get() = regions.targetSize
  val patchSize: Long get() = regions.patchSize
}
