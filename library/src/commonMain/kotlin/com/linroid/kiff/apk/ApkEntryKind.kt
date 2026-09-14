package com.linroid.kiff.apk

/** What an APK entry is, which is what makes a size comparison between two builds readable. */
enum class ApkEntryKind {
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
