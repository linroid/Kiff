package com.linroid.kiff

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

/** Entry-level differences grouped the way an APK is actually built. */
data class ApkDiffReport(
  val entries: ZipDiffReport,
  val kinds: List<ApkKindSummary>,
  /** Size of the APK signing block, or 0 when the archive is not signed with v2 or later. */
  val sourceSigningBlockSize: Int,
  val targetSigningBlockSize: Int
) {
  val signed: Boolean get() = sourceSigningBlockSize > 0 || targetSigningBlockSize > 0
}
