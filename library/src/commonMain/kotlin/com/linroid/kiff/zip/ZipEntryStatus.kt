package com.linroid.kiff.zip

enum class ZipEntryStatus {
  /** Byte-identical local record, header included. */
  UNCHANGED,

  /** Same data, different local header - a timestamp or alignment padding changed. */
  METADATA_CHANGED,
  MODIFIED,
  ADDED,
  REMOVED
}
