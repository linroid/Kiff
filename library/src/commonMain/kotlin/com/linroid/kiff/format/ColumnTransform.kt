package com.linroid.kiff.format

/**
 * Rearranges a table of fixed-width rows so that it can be compared at all.
 *
 * A table of ids is the renumbering problem in its purest form. Every entry of a dex's
 * `string_ids` is an offset into the file; insert one string and every later offset shifts, so no
 * two builds share a single matching run and the table is carried whole. The *gaps* between those
 * offsets, though, are the string lengths, and those barely change at all.
 *
 * So: read the table column by column rather than row by row, and store each column as differences
 * from the entry before it. What was a list of shifting absolutes becomes a list of stable gaps,
 * and the search has something to match again.
 *
 * Both directions are exact, and deliberately so - this only ever moves bytes around, never
 * approximates them, so a region described this way restores byte for byte like any other. A
 * trailing partial row is copied across untouched.
 */
internal object ColumnTransform {

  /** Columns to differences, row-major to column-major. */
  fun forward(bytes: ByteArray, from: Int, to: Int, widths: List<Int>): ByteArray {
    val width = widths.sum()
    val length = to - from
    val rows = length / width
    val out = ByteArray(length)
    var at = 0
    var column = 0
    for (fieldWidth in widths) {
      val mask = maskOf(fieldWidth)
      var previous = 0L
      for (row in 0 until rows) {
        var value = 0L
        val base = from + row * width + column
        for (i in 0 until fieldWidth) {
          value = value or ((bytes[base + i].toLong() and 0xFF) shl (8 * i))
        }
        val difference = (value - previous) and mask
        previous = value
        for (i in 0 until fieldWidth) {
          out[at++] = ((difference shr (8 * i)) and 0xFF).toByte()
        }
      }
      column += fieldWidth
    }
    var tail = from + rows * width
    while (tail < to) out[at++] = bytes[tail++]
    return out
  }

  /** Differences back to columns, column-major to row-major. */
  fun inverse(transformed: ByteArray, widths: List<Int>, length: Int): ByteArray {
    val width = widths.sum()
    val rows = length / width
    val out = ByteArray(length)
    var at = 0
    var column = 0
    for (fieldWidth in widths) {
      val mask = maskOf(fieldWidth)
      var previous = 0L
      for (row in 0 until rows) {
        var difference = 0L
        for (i in 0 until fieldWidth) {
          difference = difference or ((transformed[at++].toLong() and 0xFF) shl (8 * i))
        }
        val value = (previous + difference) and mask
        previous = value
        val base = row * width + column
        for (i in 0 until fieldWidth) {
          out[base + i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
      }
      column += fieldWidth
    }
    var tail = rows * width
    while (tail < length) out[tail++] = transformed[at++]
    return out
  }

  /** Whether [widths] can describe a region of [length] bytes and is worth the trouble. */
  fun suits(widths: List<Int>?, length: Int): Boolean {
    if (widths.isNullOrEmpty()) return false
    if (widths.any { it !in 1..8 }) return false
    val width = widths.sum()
    return width in 1..MAX_ROW && length / width >= MIN_ROWS
  }

  private fun maskOf(width: Int): Long =
    if (width >= 8) -1L else (1L shl (8 * width)) - 1

  /** Below this many rows there is nothing for the differences to reveal. */
  private const val MIN_ROWS = 8
  private const val MAX_ROW = 255
}
