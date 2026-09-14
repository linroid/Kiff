package com.linroid.kiff.apk

data class ApkKindSummary(
  val kind: ApkEntryKind,
  /** Entries of this kind in the target archive. */
  val entryCount: Int,
  /** Entries present in both archives whose bytes are not identical. */
  val changedCount: Int,
  /** Entries of this kind that only the target has. */
  val addedCount: Int,
  /** Entries of this kind that only the source had. */
  val removedCount: Int,
  val sourceBytes: Long,
  val targetBytes: Long
) {
  val growth: Long get() = targetBytes - sourceBytes
}
