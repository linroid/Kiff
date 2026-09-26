package com.linroid.kiff.text

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MyersDiffAlgorithmTest {

  private val algorithm = MyersDiffAlgorithm()
  private val engine = LineDiffEngine(algorithm)

  @Test
  fun nameIsCorrect() {
    assertEquals("myers", algorithm.name)
  }

  // --- Edge cases ---

  @Test
  fun bothEmpty() {
    val edits = algorithm.diff(emptyList(), emptyList())
    assertTrue(edits.isEmpty())
  }

  @Test
  fun identicalInputs() {
    val lines = listOf("a", "b", "c")
    val edits = algorithm.diff(lines, lines)
    assertEquals(listOf(Edit.Equal(0, 3)), edits)
  }

  @Test
  fun emptyToNonEmpty() {
    val target = listOf("a", "b", "c")
    val edits = algorithm.diff(emptyList(), target)
    assertEquals(listOf(Edit.Insert(0, target)), edits)
  }

  @Test
  fun nonEmptyToEmpty() {
    val source = listOf("a", "b", "c")
    val edits = algorithm.diff(source, emptyList())
    assertEquals(listOf(Edit.Delete(0, 3)), edits)
  }

  // --- Single operations ---

  @Test
  fun singleInsertion() {
    val source = listOf("a", "b")
    val target = listOf("a", "x", "b")
    val edits = algorithm.diff(source, target)
    assertRoundtrip(source, target)
    assertTrue(edits.any { it is Edit.Insert })
  }

  @Test
  fun singleDeletion() {
    val source = listOf("a", "x", "b")
    val target = listOf("a", "b")
    val edits = algorithm.diff(source, target)
    assertRoundtrip(source, target)
    assertTrue(edits.any { it is Edit.Delete })
  }

  @Test
  fun singleReplacement() {
    val source = listOf("a", "b", "c")
    val target = listOf("a", "x", "c")
    val edits = algorithm.diff(source, target)
    assertRoundtrip(source, target)
    assertTrue(edits.any { it is Edit.Delete })
    assertTrue(edits.any { it is Edit.Insert })
  }

  // --- Position variants ---

  @Test
  fun insertAtBeginning() {
    val source = listOf("a", "b")
    val target = listOf("x", "a", "b")
    assertRoundtrip(source, target)
  }

  @Test
  fun insertAtEnd() {
    val source = listOf("a", "b")
    val target = listOf("a", "b", "x")
    assertRoundtrip(source, target)
  }

  @Test
  fun deleteAtBeginning() {
    val source = listOf("x", "a", "b")
    val target = listOf("a", "b")
    assertRoundtrip(source, target)
  }

  @Test
  fun deleteAtEnd() {
    val source = listOf("a", "b", "x")
    val target = listOf("a", "b")
    assertRoundtrip(source, target)
  }

  // --- Complex cases ---

  @Test
  fun multipleScatteredChanges() {
    val source = listOf("a", "b", "c", "d", "e")
    val target = listOf("a", "x", "c", "y", "e")
    assertRoundtrip(source, target)
  }

  @Test
  fun completelyDifferent() {
    val source = listOf("a", "b", "c")
    val target = listOf("x", "y", "z")
    assertRoundtrip(source, target)
  }

  @Test
  fun duplicateLines() {
    val source = listOf("a", "a", "a")
    val target = listOf("a", "b", "a", "a")
    assertRoundtrip(source, target)
  }

  // --- Correctness ---

  @Test
  fun shortestEditScript() {
    // "a b c" -> "a x c" should have edit distance 2 (1 delete + 1 insert)
    val source = listOf("a", "b", "c")
    val target = listOf("a", "x", "c")
    val edits = algorithm.diff(source, target)
    val editDistance = edits.sumOf {
      when (it) {
        is Edit.Delete -> it.count
        is Edit.Insert -> it.lines.size
        is Edit.Equal -> 0
      }
    }
    assertEquals(2, editDistance)
  }

  @Test
  fun roundtripForAllCases() {
    val cases = listOf(
      emptyList<String>() to emptyList(),
      listOf("a") to listOf("a"),
      emptyList<String>() to listOf("a", "b"),
      listOf("a", "b") to emptyList(),
      listOf("a", "b", "c") to listOf("a", "b", "c"),
      listOf("a", "b", "c") to listOf("x", "y", "z"),
      listOf("a", "b", "c", "d") to listOf("a", "c", "d"),
      listOf("a", "c", "d") to listOf("a", "b", "c", "d"),
      listOf("a", "b", "c") to listOf("a", "x", "b", "y", "c", "z"),
      listOf("a", "b", "c", "d", "e") to listOf("b", "c", "d"),
      listOf("b", "c", "d") to listOf("a", "b", "c", "d", "e"),
    )
    for ((source, target) in cases) {
      assertRoundtrip(source, target)
    }
  }

  // --- Over inputs nobody wrote by hand ---

  /** The length of a shortest edit script, from the textbook table of common subsequences. */
  private fun shortestDistance(source: List<String>, target: List<String>): Int {
    val common = Array(source.size + 1) { IntArray(target.size + 1) }
    for (i in source.indices.reversed()) {
      for (j in target.indices.reversed()) {
        common[i][j] = if (source[i] == target[j]) {
          common[i + 1][j + 1] + 1
        } else {
          maxOf(common[i + 1][j], common[i][j + 1])
        }
      }
    }
    return source.size + target.size - 2 * common[0][0]
  }

  private fun cost(edits: List<Edit>): Int = edits.sumOf {
    when (it) {
      is Edit.Delete -> it.count
      is Edit.Insert -> it.lines.size
      is Edit.Equal -> 0
    }
  }

  /** Few distinct lines, so there is plenty in common and many shortest paths to choose from. */
  private fun randomLines(random: Random, size: Int) = List(size) { "line ${random.nextInt(4)}" }

  @Test
  fun everyScriptIsAShortestOneAndRestores() {
    val random = Random(42)
    repeat(2_000) {
      val source = randomLines(random, random.nextInt(0, 30))
      val target = randomLines(random, random.nextInt(0, 30))
      val edits = algorithm.diff(source, target)
      assertEquals(shortestDistance(source, target), cost(edits), "source=$source target=$target")
      assertRoundtrip(source, target)
      for ((first, second) in edits.zipWithNext()) {
        // Runs are whole, and within a run of changes deletions come first - the order a unified
        // diff prints them in.
        assertTrue(first::class != second::class, "split run in $edits")
        assertTrue(first !is Edit.Insert || second !is Edit.Delete, "insert before delete: $edits")
      }
    }
  }

  @Test
  fun aBudgetIsHonouredToTheEdit() {
    val random = Random(7)
    repeat(1_000) {
      val source = randomLines(random, random.nextInt(0, 24))
      val target = randomLines(random, random.nextInt(0, 24))
      val distance = shortestDistance(source, target)
      val within = assertNotNull(algorithm.diffWithin(source, target, distance), "at $distance")
      assertEquals(distance, cost(within))
      if (distance > 0) assertNull(algorithm.diffWithin(source, target, distance - 1))
    }
  }

  @Test
  fun thousandsOfChangedLinesTakeLittleMemory() {
    // The textbook search kept its frontier for every edit, which is memory in the square of the
    // edit count: six thousand edits ran a 512 MB heap out of memory.
    val source = List(3_000) { "old $it" }
    val target = List(3_000) { "new $it" }
    assertEquals(6_000, cost(algorithm.diff(source, target)))
    assertRoundtrip(source, target)

    val interleaved = List(4_000) { if (it % 2 == 0) "kept $it" else "old $it" }
    val edited = List(4_000) { if (it % 2 == 0) "kept $it" else "new $it" }
    assertEquals(4_000, cost(algorithm.diff(interleaved, edited)))
    assertRoundtrip(interleaved, edited)
  }

  @Test
  fun anAlgorithmThatCannotGiveUpEarlyIsStillHeldToItsBudget() {
    val wholeOnly = object : LineDiffAlgorithm {
      override val name = "whole only"
      override fun diff(source: List<String>, target: List<String>) = algorithm.diff(source, target)
    }
    val source = listOf("a", "b", "c")
    val target = listOf("x", "b", "y")
    assertNull(wholeOnly.diffWithin(source, target, 3))
    assertEquals(algorithm.diff(source, target), wholeOnly.diffWithin(source, target, 4))
  }

  private fun assertRoundtrip(source: List<String>, target: List<String>) {
    val patch = engine.generatePatch(source, target)
    val recovered = engine.applyPatch(source, patch)
    assertEquals(
      target,
      recovered,
      "Roundtrip failed for source=$source, target=$target, edits=${patch.edits}",
    )
  }
}
