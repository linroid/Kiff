package com.linroid.kiff.text

import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextRegionTest {

  private val algorithm = MyersDiffAlgorithm()

  /** Encodes and replays a region, which is the only claim that matters: the bytes come back. */
  private fun roundTrip(source: String, target: String): Int {
    val sourceBytes = source.encodeToByteArray()
    val targetBytes = target.encodeToByteArray()
    val literals = ByteWriter(64)
    val edits = assertNotNull(
      TextRegion.encode(
        algorithm,
        sourceBytes, 0, sourceBytes.size,
        targetBytes, 0, targetBytes.size,
        literals
      )
    )
    val restored = ByteArray(targetBytes.size)
    val consumed = TextRegion.apply(
      source = sourceBytes,
      sourceFrom = 0,
      sourceLength = sourceBytes.size,
      edits = ByteReader(edits),
      literals = literals.toByteArray(),
      literalFrom = 0,
      target = restored,
      at = 0,
      length = targetBytes.size
    )
    assertContentEquals(targetBytes, restored, "restored text differs")
    assertEquals(literals.size, consumed, "should consume exactly what it wrote")
    return edits.size + literals.size
  }

  @Test
  fun aTrailingNewlineIsNotLost() {
    // The CLI's line reader drops this distinction; a patcher may not.
    roundTrip("alpha\nbeta\n", "alpha\nbeta")
    roundTrip("alpha\nbeta", "alpha\nbeta\n")
  }

  @Test
  fun trailingBlankLinesSurvive() {
    roundTrip("alpha\n\n\n", "alpha\n\n\n\n")
    roundTrip("alpha\n\n\n\n", "alpha\n")
  }

  @Test
  fun carriageReturnsBelongToTheirLine() {
    roundTrip("alpha\r\nbeta\r\n", "alpha\r\ngamma\r\nbeta\r\n")
  }

  @Test
  fun anEmptySourceOrTargetStillRoundTrips() {
    roundTrip("", "alpha\nbeta\n")
    roundTrip("alpha\nbeta\n", "")
  }

  @Test
  fun aSingleLineWithNoTerminatorRoundTrips() {
    roundTrip("alpha", "alpha beta")
  }

  @Test
  fun anInsertedLineInTheMiddleCostsOnlyThatLine() {
    val source = (1..200).joinToString("\n") { "line $it" } + "\n"
    val target = source.replace("line 100\n", "line 100\ninserted\n")
    val cost = roundTrip(source, target)
    assertTrue(cost < 120, "one inserted line should cost about one line, got $cost bytes")
  }

  @Test
  fun theSearchDeclinesWhenThereAreTooManyLines() {
    val source = "x\n".repeat(TextRegion.MAX_LINES + 1).encodeToByteArray()
    val target = "y\n".repeat(TextRegion.MAX_LINES + 1).encodeToByteArray()
    assertNull(
      TextRegion.encode(
        algorithm,
        source, 0, source.size,
        target, 0, target.size,
        ByteWriter(64)
      ),
      "should decline rather than run a quadratic search"
    )
  }

  @Test
  fun linesCarryTheirOwnTerminators() {
    val bytes = "a\nbb\nccc".encodeToByteArray()
    val starts = TextRegion.lineStarts(bytes, 0, bytes.size)
    assertEquals(listOf(0, 2, 5, 8), starts.toList())
  }

  @Test
  fun aFinalNewlineDoesNotOpenAnEmptyLine() {
    val bytes = "a\nb\n".encodeToByteArray()
    val starts = TextRegion.lineStarts(bytes, 0, bytes.size)
    assertEquals(listOf(0, 2, 4), starts.toList(), "two lines, not three")
  }
}
