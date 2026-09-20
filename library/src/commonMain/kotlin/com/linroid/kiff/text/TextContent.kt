package com.linroid.kiff.text

/**
 * A text file as the lines a diff talks about, plus the one thing that is easy to lose.
 *
 * Unified diff names lines without their terminators, so whether the file ended in a newline has
 * to be carried separately or it is gone - which is exactly what the CLI's older line reader got
 * wrong, dropping empty trailing entries and so making a file that ends in a newline
 * indistinguishable from one that does not. That is fine for showing a diff and fatal for applying
 * one.
 *
 * A carriage return stays part of the line it ends, so CRLF files round trip without a special
 * case.
 */
data class TextContent(val lines: List<String>, val endsWithNewline: Boolean) {

  fun toBytes(): ByteArray {
    if (lines.isEmpty()) return ByteArray(0)
    val joined = lines.joinToString("\n")
    return (if (endsWithNewline) "$joined\n" else joined).encodeToByteArray()
  }

  companion object {
    fun of(bytes: ByteArray): TextContent = of(bytes.decodeToString())

    fun of(text: String): TextContent {
      if (text.isEmpty()) return TextContent(emptyList(), endsWithNewline = false)
      val endsWithNewline = text.endsWith("\n")
      val body = if (endsWithNewline) text.dropLast(1) else text
      return TextContent(body.split("\n"), endsWithNewline)
    }
  }
}
