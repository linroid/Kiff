package com.linroid.kiff.apk

/**
 * What an APK entry is, which is what makes a size comparison between two builds readable.
 *
 * A minor release may tell more kinds apart, so a `when` over these keeps an `else` branch.
 */
public enum class ApkEntryKind {
  DEX,
  NATIVE_LIBRARY,
  MANIFEST,
  RESOURCE_TABLE,
  RESOURCE,
  ASSET,
  SIGNATURE,
  METADATA,
  OTHER
}
