package com.linroid.kiff.zip

/**
 * How an entry differs between two archives.
 *
 * A minor release may tell more cases apart, so a `when` over these keeps an `else` branch.
 */
public enum class ZipEntryStatus {
  /** Byte-identical local record, header included. */
  UNCHANGED,

  /** Same data, different local header - a timestamp or alignment padding changed. */
  METADATA_CHANGED,
  MODIFIED,
  ADDED,
  REMOVED
}
