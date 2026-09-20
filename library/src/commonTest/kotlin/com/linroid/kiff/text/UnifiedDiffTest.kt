package com.linroid.kiff.text

import com.linroid.kiff.KiffException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnifiedDiffTest {

  private val engine = LineDiffEngine(MyersDiffAlgorithm())

  /** Diff two texts, print them, read them back, and check the target comes out. */
  private fun roundTrip(source: String, target: String): String {
    val a = TextContent.of(source)
    val b = TextContent.of(target)
    val diff = UnifiedDiff.format(a, b, engine.generatePatch(a.lines, b.lines).edits)
    if (source == target) {
      assertEquals("", diff, "identical files should produce no diff at all")
      return diff
    }
    val applied = UnifiedDiff.apply(a, UnifiedDiff.parse(diff))
    assertContentEquals(
      b.toBytes(),
      applied.toBytes(),
      "round trip differs\n--- diff ---\n$diff"
    )
    return diff
  }

  @Test
  fun aChangedLineRoundTrips() {
    val diff = roundTrip(
      "alpha\nbeta\ngamma\n",
      "alpha\nbeta changed\ngamma\n"
    )
    assertTrue(diff.startsWith("--- a\n+++ b\n@@ "), diff)
    assertTrue("-beta" in diff && "+beta changed" in diff, diff)
  }

  @Test
  fun insertionsAndDeletionsRoundTrip() {
    roundTrip("one\ntwo\nthree\n", "one\ninserted\ntwo\nthree\n")
    roundTrip("one\ntwo\nthree\n", "one\nthree\n")
    roundTrip("one\ntwo\nthree\n", "")
    roundTrip("", "one\ntwo\n")
  }

  @Test
  fun aMissingTrailingNewlineIsCarried() {
    // The distinction the CLI's older line reader threw away.
    val diff = roundTrip("alpha\nbeta\n", "alpha\nbeta")
    assertTrue("\\ No newline at end of file" in diff, diff)
    roundTrip("alpha\nbeta", "alpha\nbeta\n")
  }

  @Test
  fun carriageReturnsStayPartOfTheirLine() {
    roundTrip("alpha\r\nbeta\r\n", "alpha\r\ngamma\r\nbeta\r\n")
  }

  @Test
  fun changesFarApartBecomeSeparateHunks() {
    val source = (1..60).joinToString("\n") { "line $it" } + "\n"
    val target = source.replace("line 5\n", "line 5 edited\n").replace("line 55\n", "line 55 edited\n")
    val diff = roundTrip(source, target)
    assertEquals(2, diff.lines().count { it.startsWith("@@") }, diff)
  }

  @Test
  fun changesCloseTogetherShareAHunk() {
    val source = (1..20).joinToString("\n") { "line $it" } + "\n"
    val target = source.replace("line 5\n", "line 5 edited\n").replace("line 6\n", "line 6 edited\n")
    val diff = roundTrip(source, target)
    assertEquals(1, diff.lines().count { it.startsWith("@@") }, diff)
  }

  @Test
  fun identicalFilesProduceNothing() {
    roundTrip("alpha\nbeta\n", "alpha\nbeta\n")
  }

  @Test
  fun aHunkWhoseContextDoesNotMatchIsRefused() {
    // The check that makes applying a diff safe rather than hopeful.
    val diff = """
      |--- a
      |+++ b
      |@@ -1,2 +1,2 @@
      | not the source at all
      |-beta
      |+beta changed
    """.trimMargin()
    val failure = assertFailsWith<KiffException.InvalidPatch> {
      UnifiedDiff.apply(TextContent.of("alpha\nbeta\n"), UnifiedDiff.parse(diff))
    }
    assertTrue("does not match" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun aDiffWrittenByHandIsAccepted() {
    // Whatever produced it, if it reads as unified diff it should apply.
    val diff = """
      |--- old.txt
      |+++ new.txt
      |@@ -1,3 +1,3 @@
      | alpha
      |-beta
      |+BETA
      | gamma
    """.trimMargin()
    val patch = UnifiedDiff.parse(diff)
    assertEquals("old.txt", patch.sourceName)
    assertEquals("new.txt", patch.targetName)
    val applied = UnifiedDiff.apply(TextContent.of("alpha\nbeta\ngamma\n"), patch)
    assertEquals("alpha\nBETA\ngamma\n", applied.toBytes().decodeToString())
  }

  @Test
  fun textContentRoundTripsAnyBytesItSplits() {
    for (text in listOf("", "\n", "\n\n\n", "a", "a\n", "a\nb", "a\nb\n", "\na\n")) {
      assertEquals(
        text,
        TextContent.of(text).toBytes().decodeToString(),
        "TextContent lost something in '$text'"
      )
    }
  }
}
