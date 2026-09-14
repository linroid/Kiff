package com.linroid.kiff

enum class ZipEntryStatus {
  /** Byte-identical local record, header included. */
  UNCHANGED,

  /** Same data, different local header - a timestamp or alignment padding changed. */
  METADATA_CHANGED,
  MODIFIED,
  ADDED,
  REMOVED
}

data class ZipEntryChange(
  val name: String,
  val status: ZipEntryStatus,
  /** Compressed size in the source archive, or 0 when the entry is new. */
  val sourceSize: Int,
  /** Compressed size in the target archive, or 0 when the entry is gone. */
  val targetSize: Int,
  /** True when the entry is stored uncompressed, so a delta sees its real content. */
  val stored: Boolean
)

/** Entry-level summary of what differs between two archives. */
data class ZipDiffReport(
  val changes: List<ZipEntryChange>,
  val sourceSize: Int,
  val targetSize: Int
) {
  fun count(status: ZipEntryStatus): Int = changes.count { it.status == status }

  fun bytes(status: ZipEntryStatus): Long = changes
    .filter { it.status == status }
    .sumOf { (if (status == ZipEntryStatus.REMOVED) it.sourceSize else it.targetSize).toLong() }

  val changed: List<ZipEntryChange>
    get() = changes.filter { it.status != ZipEntryStatus.UNCHANGED }
}
