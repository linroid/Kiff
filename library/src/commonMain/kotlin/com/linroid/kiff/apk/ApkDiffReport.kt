package com.linroid.kiff.apk

import com.linroid.kiff.zip.ZipDiffReport

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
