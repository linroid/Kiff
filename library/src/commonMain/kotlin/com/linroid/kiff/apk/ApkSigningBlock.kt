package com.linroid.kiff.apk

/**
 * Locates the APK signing block, the unnamed run of bytes a v2+ signed APK carries between its last
 * entry and the central directory:
 *
 * ```
 * uint64 size | id-value pairs | uint64 size | "APK Sig Block 42"
 * ```
 */
internal object ApkSigningBlock {

  private val MAGIC = "APK Sig Block 42".encodeToByteArray()
  private const val FOOTER_SIZE = 24 // uint64 size + 16 byte magic

  /** Byte range of the block, or null when the archive has none. */
  fun findOrNull(bytes: ByteArray, directoryStart: Int): IntRange? {
    if (directoryStart < FOOTER_SIZE || directoryStart > bytes.size) return null
    val magicStart = directoryStart - MAGIC.size
    for (i in MAGIC.indices) {
      if (bytes[magicStart + i] != MAGIC[i]) return null
    }
    val size = readUInt64(bytes, directoryStart - FOOTER_SIZE)
    if (size < FOOTER_SIZE || size > directoryStart - 8L) return null
    val start = directoryStart - size.toInt() - 8
    if (start < 0) return null
    return start until directoryStart
  }

  fun sizeOf(bytes: ByteArray, directoryStart: Int): Int =
    findOrNull(bytes, directoryStart)?.let { it.last - it.first + 1 } ?: 0

  private fun readUInt64(bytes: ByteArray, at: Int): Long {
    var value = 0L
    for (i in 7 downTo 0) {
      value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
    }
    return value
  }
}
