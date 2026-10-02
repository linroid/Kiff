package com.linroid.kiff.text

/**
 * Bytes as a string of one character per byte, so line-based text code can work on bytes in any
 * encoding. The mapping is the identity on 0..255, so nothing is replaced and [charsAsBytes] gives
 * the bytes back exactly.
 */
internal fun bytesAsChars(bytes: ByteArray): String {
  val chars = CharArray(bytes.size)
  for (i in bytes.indices) chars[i] = (bytes[i].toInt() and 0xFF).toChar()
  return chars.concatToString()
}

/** The bytes [bytesAsChars] was given. Every character must be below 256. */
internal fun charsAsBytes(text: String): ByteArray {
  val bytes = ByteArray(text.length)
  for (i in text.indices) {
    val code = text[i].code
    require(code < 256) { "Character ${text[i]} at $i is not a byte" }
    bytes[i] = code.toByte()
  }
  return bytes
}
