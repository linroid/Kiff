package com.linroid.kiff.apk

import com.linroid.kiff.ApkEntryKind

internal object ApkEntries {

  private val signatureSuffixes = listOf(".SF", ".RSA", ".DSA", ".EC")

  fun kindOf(name: String): ApkEntryKind = when {
    name == "AndroidManifest.xml" -> ApkEntryKind.MANIFEST
    name == "resources.arsc" -> ApkEntryKind.RESOURCE_TABLE
    dexOrdinal(name) != null -> ApkEntryKind.DEX
    name.startsWith("lib/") && name.endsWith(".so") -> ApkEntryKind.NATIVE_LIBRARY
    name.startsWith("res/") -> ApkEntryKind.RESOURCE
    name.startsWith("assets/") -> ApkEntryKind.ASSET
    isSignature(name) -> ApkEntryKind.SIGNATURE
    name.startsWith("META-INF/") -> ApkEntryKind.METADATA
    else -> ApkEntryKind.OTHER
  }

  private fun isSignature(name: String): Boolean {
    if (!name.startsWith("META-INF/")) return false
    if (name == "META-INF/MANIFEST.MF") return true
    return signatureSuffixes.any { name.endsWith(it) }
  }

  /** 1 for `classes.dex`, 2 for `classes2.dex`, and so on; null when the name is not a dex. */
  fun dexOrdinal(name: String): Int? {
    if (!name.startsWith("classes") || !name.endsWith(".dex")) return null
    val digits = name.substring("classes".length, name.length - ".dex".length)
    if (digits.isEmpty()) return 1
    if (digits.any { !it.isDigit() }) return null
    // classes1.dex is not a name the platform uses, so treat it as an ordinary entry.
    return digits.toIntOrNull()?.takeIf { it >= 2 }
  }
}
