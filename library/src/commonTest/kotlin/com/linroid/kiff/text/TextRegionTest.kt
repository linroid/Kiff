package com.linroid.kiff.text

import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.io.ByteArrayRestoreTarget
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    val restored = ByteArrayRestoreTarget(targetBytes.size)
    val consumed = TextRegion.apply(
      source = sourceBytes,
      edits = ByteReader(edits),
      literals = literals.toByteArray(),
      literalFrom = 0,
      out = restored,
      length = targetBytes.size
    )
    assertContentEquals(targetBytes, restored.toByteArray(), "restored text differs")
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
  fun theSearchDeclinesWhenTheScriptWouldBeTooLong() {
    // Well within the line cap, but every line changed: twice as many edits as lines.
    fun lines(count: Int, word: String) =
      (1..count).joinToString("") { "$word $it\n" }.encodeToByteArray()

    val over = TextRegion.MAX_EDITS / 2 + 1
    val source = lines(over, "old")
    val target = lines(over, "new")
    assertNull(
      TextRegion.encode(
        algorithm,
        source, 0, source.size,
        target, 0, target.size,
        ByteWriter(64)
      ),
      "should leave a rewritten region to the byte search"
    )
    val within = TextRegion.MAX_EDITS / 2
    roundTrip(lines(within, "old").decodeToString(), lines(within, "new").decodeToString())
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

  /** Replays a hand-written script over [source] into a region of [length] bytes. */
  private fun replay(source: ByteArray, length: Int, script: ByteWriter.() -> Unit): ByteArray {
    val restored = ByteArrayRestoreTarget(length)
    TextRegion.apply(
      source = source,
      edits = ByteReader(ByteWriter(16).apply(script).toByteArray()),
      literals = ByteArray(0),
      literalFrom = 0,
      out = restored,
      length = length
    )
    return restored.toByteArray()
  }

  @Test
  fun aScriptFindsTheSameLinesTheIndexDoes() {
    // The reader walks to each line rather than indexing them all, and has to land exactly where
    // the index the encoder used says each line starts.
    val random = Random(3)
    repeat(2_000) {
      val source = ByteArray(random.nextInt(0, 24)) { "ab\r\n"[random.nextInt(4)].code.toByte() }
      val starts = TextRegion.lineStarts(source, 0, source.size)
      val lines = starts.size - 1
      val skipped = random.nextInt(0, lines + 1)
      val kept = random.nextInt(0, lines - skipped + 1)
      val expected = source.copyOfRange(starts[skipped], starts[skipped + kept])
      val restored = replay(source, expected.size) {
        writeByte(2) // delete
        writeVarInt(skipped)
        writeByte(1) // equal
        writeVarInt(kept)
        writeByte(0)
      }
      assertContentEquals(expected, restored, source.decodeToString())
    }
  }

  @Test
  fun everyLineOfALongRangeIsReachable() {
    val source = ByteArray(1 shl 20) { '\n'.code.toByte() }
    val restored = replay(source, source.size) {
      writeByte(1)
      writeVarInt(source.size)
      writeByte(0)
    }
    assertContentEquals(source, restored)
  }

  @Test
  fun aCountPastTheLastLineIsRefused() {
    for (text in listOf("", "a", "a\n", "a\r\nb")) {
      val source = text.encodeToByteArray()
      val lines = TextRegion.lineStarts(source, 0, source.size).size - 1
      for (count in listOf(lines + 1, Int.MAX_VALUE)) {
        for (op in listOf(1, 2)) {
          assertFailsWith<KiffException.InvalidPatch>("'$text' op $op count $count") {
            replay(source, source.size) {
              writeByte(op)
              writeVarInt(count)
              writeByte(0)
            }
          }
        }
      }
    }
  }
}
