package com.linroid.kiff.text

import com.linroid.kiff.KiffException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnifiedDiffTest {

  private val engine = LineDiffEngine(MyersDiffAlgorithm())

  /** Diff two texts, print them, read them back, and check the target comes out. */
  private fun roundTrip(source: String, target: String, context: Int = 3): String {
    val a = TextContent.of(source)
    val b = TextContent.of(target)
    val diff = UnifiedDiff.format(
      a, b, engine.generatePatch(a.lines, b.lines).edits, context = context
    )
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
  fun changesTwiceTheContextApartShareAHunk() {
    // Six unchanged lines is exactly what two hunks' context would cover between them, so GNU diff
    // and git print one hunk rather than two that touch.
    val source = (1..10).joinToString("") { "$it\n" }
    val target = source.replace("2\n", "X\n").replace("9\n", "Y\n")
    val diff = roundTrip(source, target)
    assertEquals(listOf("@@ -1,10 +1,10 @@"), headers(diff), diff)
  }

  @Test
  fun changesFurtherApartThanTwiceTheContextStaySeparate() {
    val source = (1..11).joinToString("") { "$it\n" }
    val target = source.replace("2\n", "X\n").replace("10\n", "Y\n")
    val diff = roundTrip(source, target)
    assertEquals(listOf("@@ -1,5 +1,5 @@", "@@ -7,5 +7,5 @@"), headers(diff), diff)
  }

  @Test
  fun anInsertionWithoutContextLandsWhereItWasMade() {
    // A range of no lines is numbered by the line it follows. Numbered from zero, this insertion
    // went to the top of the file, and patch(1) put it there without a word.
    val diff = roundTrip("a\nb\nc\nd\ne\n", "a\nb\nc\nd\nINS\ne\n", context = 0)
    assertEquals(listOf("@@ -4,0 +5 @@"), headers(diff), diff)
  }

  @Test
  fun aReplacementWithoutContextIsOneHunk() {
    val diff = roundTrip("1\n2\n3\n4\n5\n6\n", "1\n2\nX\nY\n5\n6\n", context = 0)
    assertEquals(listOf("@@ -3,2 +3,2 @@"), headers(diff), diff)
  }

  @Test
  fun aDeletionWithoutContextIsNumberedOnBothSides() {
    val diff = roundTrip("a\nb\nc\nd\ne\n", "a\nb\nc\nd\n", context = 0)
    assertEquals(listOf("@@ -5 +4,0 @@"), headers(diff), diff)
  }

  @Test
  fun zeroCountHunksFromOtherToolsApplyInPlace() {
    val source = TextContent.of("a\nb\nc\nd\ne\n")
    fun applied(hunks: String): String {
      val patch = UnifiedDiff.parse("--- a\n+++ b\n$hunks")
      return UnifiedDiff.apply(source, patch).toBytes().decodeToString()
    }

    assertEquals("a\nb\nc\nd\nINS\ne\n", applied("@@ -4,0 +5 @@\n+INS\n"))
    assertEquals("TOP\na\nb\nc\nd\ne\n", applied("@@ -0,0 +1 @@\n+TOP\n"))
    assertEquals("a\nb\nc\nd\n", applied("@@ -5 +4,0 @@\n-e\n"))
  }

  @Test
  fun aHunkNumberedZeroThatClaimsLinesIsRefused() {
    assertFailsWith<KiffException.InvalidPatch> {
      UnifiedDiff.apply(
        TextContent.of("a\nb\n"),
        UnifiedDiff.parse("--- a\n+++ b\n@@ -0,1 +0,0 @@\n-a\n")
      )
    }
  }

  @Test
  fun laterHunksCountWhatEarlierOnesChanged() {
    val source = (1..40).joinToString("") { "line $it\n" }
    val target = source
      .replace("line 5\n", "line 5\nnew 1\nnew 2\nnew 3\n")
      .replace("line 30\n", "line 30 edited\n")
    val diff = roundTrip(source, target)
    assertEquals(listOf("@@ -3,6 +3,9 @@", "@@ -27,7 +30,7 @@"), headers(diff), diff)
  }

  @Test
  fun anEmptiedFileIsNumberedFromZeroOnTheTargetSide() {
    val diff = roundTrip("a\nb\nc\n", "")
    assertEquals(listOf("@@ -1,3 +0,0 @@"), headers(diff), diff)
  }

  @Test
  fun everyContextRoundTripsAndNumbersBothSides() {
    val random = Random(1)
    val alphabet = listOf("a", "b", "c", "d", "e", "f")
    fun text() = List(random.nextInt(0, 12)) { alphabet.random(random) }.joinToString("") { "$it\n" }
    repeat(1_500) {
      val source = text()
      val target = text()
      val context = random.nextInt(0, 4)
      val diff = roundTrip(source, target, context)
      if (diff.isEmpty()) return@repeat
      // Each side is numbered on its own, so the lines before a hunk differ between the sides by
      // exactly what the hunks before it added or removed.
      var shift = 0
      for (hunk in UnifiedDiff.parse(diff).hunks) {
        val sourceBefore = hunk.sourceStart - if (hunk.sourceCount == 0) 0 else 1
        val targetBefore = hunk.targetStart - if (hunk.targetCount == 0) 0 else 1
        assertEquals(shift, targetBefore - sourceBefore, diff)
        shift += hunk.targetCount - hunk.sourceCount
      }
    }
  }

  @Test
  fun aNegativeContextIsRefused() {
    val a = TextContent.of("a\n")
    val b = TextContent.of("b\n")
    assertFailsWith<IllegalArgumentException> {
      UnifiedDiff.format(a, b, engine.generatePatch(a.lines, b.lines).edits, context = -1)
    }
  }

  @Test
  fun aHugeContextIsTheWholeFile() {
    val source = (1..10).joinToString("") { "$it\n" }
    val target = source.replace("2\n", "X\n").replace("9\n", "Y\n")
    for (context in listOf(1 shl 30, Int.MAX_VALUE)) {
      assertEquals(listOf("@@ -1,10 +1,10 @@"), headers(roundTrip(source, target, context)))
    }
  }

  private fun headers(diff: String) = diff.lines().filter { it.startsWith("@@") }

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
