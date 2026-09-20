package com.linroid.kiff.format

import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ColumnTransformTest {

  /** The only claim that matters: rearranging is exact, so nothing it touches can fail to restore. */
  private fun roundTrip(bytes: ByteArray, widths: List<Int>) {
    val forward = ColumnTransform.forward(bytes, 0, bytes.size, widths)
    val back = ColumnTransform.inverse(forward, widths, bytes.size)
    assertContentEquals(bytes, back, "widths $widths over ${bytes.size} bytes")
  }

  @Test
  fun everyRowLayoutRoundTrips() {
    val layouts = listOf(
      listOf(4),
      listOf(2, 2, 4),
      listOf(4, 4, 4),
      List(8) { 4 },
      listOf(1),
      listOf(8),
      listOf(3, 5)
    )
    for (widths in layouts) {
      roundTrip(structuredBytes(widths.sum() * 40, seed = widths.sum()), widths)
    }
  }

  @Test
  fun aTrailingPartialRowSurvives() {
    // Tables are whole rows, but a region handed to this may not be; the tail is copied across.
    val widths = listOf(2, 2, 4)
    for (extra in 1 until widths.sum()) {
      roundTrip(structuredBytes(widths.sum() * 12 + extra, seed = extra), widths)
    }
  }

  @Test
  fun valuesThatWrapRoundTripToo() {
    // Differences are stored in the field's own width, so a column that decreases wraps. It has to
    // wrap back to exactly what it was.
    val widths = listOf(4)
    val bytes = ByteArray(4 * 16)
    for (row in 0 until 16) {
      // Descending, so every difference is negative.
      val value = (1_000_000 - row * 90_000).toLong()
      for (i in 0 until 4) bytes[row * 4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }
    roundTrip(bytes, widths)
  }

  @Test
  fun everyByteValueSurvives() {
    val bytes = ByteArray(256 * 4) { (it % 256).toByte() }
    roundTrip(bytes, listOf(4))
    roundTrip(bytes, listOf(1, 1, 1, 1))
  }

  @Test
  fun aTableOfShiftingOffsetsBecomesStableGaps() {
    // The point of the whole thing. Two builds of a table of offsets, where a row was inserted
    // early so every later offset shifts. Read as they lie, the two share almost nothing; read as
    // differences, they are nearly the same table.
    fun offsets(count: Int, from: Int): ByteArray {
      val out = ByteArray(count * 4)
      var value = from
      for (row in 0 until count) {
        for (i in 0 until 4) out[row * 4 + i] = ((value shr (8 * i)) and 0xFF).toByte()
        value += 7 + (row % 5)
      }
      return out
    }
    val before = offsets(200, from = 1_000)
    val after = offsets(200, from = 1_064)

    val widths = listOf(4)
    val rearrangedBefore = ColumnTransform.forward(before, 0, before.size, widths)
    val rearrangedAfter = ColumnTransform.forward(after, 0, after.size, widths)

    var sameAsThey = 0
    for (i in before.indices) if (before[i] == after[i]) sameAsThey++
    var sameRearranged = 0
    for (i in rearrangedBefore.indices) if (rearrangedBefore[i] == rearrangedAfter[i]) sameRearranged++

    // Every gap is the same in both builds; only the first offset moved. Read as they lie, the
    // tables agree about half the time by luck; read as differences, they agree almost entirely.
    assertTrue(
      sameRearranged > before.size * 0.95,
      "differences should nearly all match: $sameRearranged of ${before.size}"
    )
    assertTrue(
      sameAsThey < before.size * 0.8,
      "the offsets themselves should not: $sameAsThey of ${before.size}"
    )
  }

  @Test
  fun aLayoutThatCannotDescribeTheRegionIsRefused() {
    assertFalse(ColumnTransform.suits(null, 100))
    assertFalse(ColumnTransform.suits(emptyList(), 100))
    assertFalse(ColumnTransform.suits(listOf(0), 100), "a zero-width field describes nothing")
    assertFalse(ColumnTransform.suits(listOf(9), 100), "wider than the arithmetic holds")
    assertFalse(ColumnTransform.suits(listOf(4), 8), "too few rows to reveal anything")
    assertTrue(ColumnTransform.suits(listOf(4), 400))
  }
}
