package com.linroid.kiff.apk

import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder

/** Builds archives shaped like an APK, including a well-formed v2 signing block. */
class TestApkBuilder {

  private val zip = TestZipBuilder()
  private var signingPayload: ByteArray? = null

  fun manifest(bytes: ByteArray = structuredBytes(2_000, seed = 500)) = apply {
    zip.entry("AndroidManifest.xml", bytes, method = 8)
  }

  fun resourceTable(bytes: ByteArray = structuredBytes(8_000, seed = 501)) = apply {
    zip.entry("resources.arsc", bytes)
  }

  fun dex(name: String, bytes: ByteArray) = apply { zip.entry(name, bytes) }

  fun nativeLibrary(name: String, bytes: ByteArray) = apply { zip.entry("lib/$name", bytes) }

  fun resource(name: String, bytes: ByteArray) = apply { zip.entry("res/$name", bytes) }

  fun signatureFiles(seed: Int) = apply {
    zip.entry("META-INF/MANIFEST.MF", structuredBytes(3_000, seed))
    zip.entry("META-INF/CERT.SF", structuredBytes(3_000, seed + 1))
    zip.entry("META-INF/CERT.RSA", structuredBytes(1_200, seed + 2))
  }

  fun signingBlock(payload: ByteArray) = apply { signingPayload = payload }

  fun build(): ByteArray {
    signingPayload?.let { zip.beforeDirectory(wrapSigningBlock(it)) }
    return zip.build()
  }

  private fun wrapSigningBlock(payload: ByteArray): ByteArray {
    val magic = "APK Sig Block 42".encodeToByteArray()
    val size = (payload.size + 8 + magic.size).toLong()
    val block = ByteArray(payload.size + 32)
    writeUInt64(block, 0, size)
    payload.copyInto(block, 8)
    writeUInt64(block, 8 + payload.size, size)
    magic.copyInto(block, 16 + payload.size)
    return block
  }

  private fun writeUInt64(target: ByteArray, at: Int, value: Long) {
    for (i in 0 until 8) {
      target[at + i] = ((value ushr (i * 8)) and 0xFF).toByte()
    }
  }
}
