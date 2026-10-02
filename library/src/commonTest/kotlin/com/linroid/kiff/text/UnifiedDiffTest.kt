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
  fun aMissingFinalNewlineSurvivesWhereverTheChangeIs() {
    // Each of these restored with a newline the target never had, or printed a diff that patch(1)
    // and git apply refuse.
    roundTrip("a\nb\nc\n", "a\nb")
    roundTrip("a\nb\nc", "X\nb\nc")
    roundTrip("a\nb\nc", "a\nX\nc")
    roundTrip("a\nb", "a")
    roundTrip("a\nb\n", "a")
    roundTrip("a\nb", "a\nb\nc\n")
    roundTrip("a\nb", "a\nb\nc")
  }

  @Test
  fun theMarkerIsPrintedWhereGnuDiffPrintsIt() {
    val marker = "\\ No newline at end of file"
    assertTrue(roundTrip("a\nb\nc", "X\nb\nc").endsWith(" c\n$marker\n"))
    assertEquals(
      " a\n-b\n$marker\n+b\n+c\n",
      body(roundTrip("a\nb", "a\nb\nc\n"))
    )
    assertEquals(
      " a\n-b\n-c\n+b\n$marker\n",
      body(roundTrip("a\nb\nc\n", "a\nb"))
    )
  }

  @Test
  fun aMarkerThatDoesNotDescribeTheEndOfAFileIsRefused() {
    val marker = "\\ No newline at end of file"
    val cases = listOf(
      // After an added line in the middle: it used to strip the newline from the whole file.
      "a\nb\nc\n" to "@@ -1,3 +1,4 @@\n a\n+x\n$marker\n b\n c\n",
      // After a removed line the source does end with a newline.
      "a\nb\n" to "@@ -1,2 +1 @@\n a\n-b\n$marker\n",
      // After nothing at all.
      "a\n" to "@@ -1 +1 @@\n$marker\n-a\n+b\n"
    )
    for ((source, hunks) in cases) {
      assertFailsWith<KiffException.InvalidPatch>(hunks) {
        UnifiedDiff.apply(TextContent.of(source), UnifiedDiff.parse("--- a\n+++ b\n$hunks"))
      }
    }
  }

  private fun body(diff: String) =
    diff.lines().drop(3).joinToString("\n")

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
    // Either side may lack a final newline, which is where most of the ways to get this wrong are.
    fun text(): String {
      val lines = List(random.nextInt(0, 12)) { alphabet.random(random) }
      return lines.joinToString("\n") + if (lines.isNotEmpty() && random.nextBoolean()) "\n" else ""
    }
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
  fun aDiffThatDoesNotHoldTogetherIsRefused() {
    val source = TextContent.of("a\nb\nc\n")
    val cases = listOf(
      // Numbers past Int used to escape as a NumberFormatException.
      "@@ -99999999999 +1 @@\n+x\n",
      "@@ -1,99999999999 +1 @@\n a\n",
      "@@ -1 +99999999999 @@\n a\n",
      "@@ -x +1 @@\n a\n",
      "garbage\n",
      // Cut short: this applied the two lines it had and returned a file that is neither side.
      "@@ -1,3 +1,4 @@\n a\n+x\n",
      // Lines past the counts, which patch(1) and git apply leave out: this applied them.
      "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n+evil\n",
      "@@ -1,99 +1 @@\n a\n",
      "@@ -1 +1 @@\n?a\n",
      "@@ -3 +3 @@\n-c\n+C\n@@ -1 +1 @@\n-a\n+A\n",
      "@@ -9 +9 @@\n-z\n+Z\n"
    )
    for (hunks in cases) {
      assertFailsWith<KiffException.InvalidPatch>(hunks) {
        UnifiedDiff.apply(source, UnifiedDiff.parse("--- a\n+++ b\n$hunks"))
      }
    }
  }

  @Test
  fun aHunkBuiltWithCountsItsBodyDoesNotHaveIsRefused() {
    val patch = UnifiedDiff.Patch("a", "b", listOf(UnifiedDiff.Hunk(1, 2, 1, 2, listOf(" a"))))
    assertFailsWith<KiffException.InvalidPatch> {
      UnifiedDiff.apply(TextContent.of("a\nb\n"), patch)
    }
  }

  @Test
  fun diffsFromOtherToolsAreRead() {
    fun applied(source: String, diff: String) =
      UnifiedDiff.apply(TextContent.of(source), UnifiedDiff.parse(diff)).toBytes().decodeToString()

    val git = """
      |diff --git a/f b/f
      |index 1234567..89abcde 100644
      |--- a/f
      |+++ b/f
      |@@ -1,3 +1,3 @@
      | a
      |-b
      |+B
      | c
      |
    """.trimMargin()
    assertEquals("a\nB\nc\n", applied("a\nb\nc\n", git))
    assertEquals("a/f", UnifiedDiff.parse(git).sourceName)

    // An empty context line that lost its leading space on the way.
    assertEquals("a\n\nB\n", applied("a\n\nb\n", "--- a\n+++ b\n@@ -1,3 +1,3 @@\n a\n\n-b\n+B\n"))
    // A marker in another language.
    assertEquals(
      "b\n",
      applied("a", "--- a\n+++ b\n@@ -1 +1 @@\n-a\n\\ Kein Zeilenumbruch am Dateiende.\n+b\n")
    )
    val stamped = "--- a.txt\t2026-01-01 00:00:00.000000000 +0000\n" +
      "+++ b.txt\t2026-01-02 00:00:00.000000000 +0000\n@@ -1 +1 @@\n-a\n+b\n"
    assertEquals("a.txt", UnifiedDiff.parse(stamped).sourceName)
    assertEquals("b.txt", UnifiedDiff.parse(stamped).targetName)
  }

  @Test
  fun headerNamesSurviveTheirRoundTrip() {
    val a = TextContent.of("a\n")
    val b = TextContent.of("b\n")
    val edits = engine.generatePatch(a.lines, b.lines).edits
    val diff = UnifiedDiff.format(a, b, edits, "my file.txt", "odd\"na\\me\n")
    // The tab is how patch(1) finds where a name with a space ends.
    assertTrue(diff.startsWith("--- my file.txt\t\n+++ \"odd\\\"na\\\\me\\n\"\n"), diff)
    val patch = UnifiedDiff.parse(diff)
    assertEquals("my file.txt", patch.sourceName)
    assertEquals("odd\"na\\me\n", patch.targetName)
    val quoted = "--- \"\\303\\274n\\303\\257code\"\n+++ b\n"
    assertEquals("ünïcode", UnifiedDiff.parse(quoted).sourceName)
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
