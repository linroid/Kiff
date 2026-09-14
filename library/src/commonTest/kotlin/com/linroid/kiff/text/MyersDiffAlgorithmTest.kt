package com.linroid.kiff.text

import kotlin.test.Test
import kotlin.test.assertEquals
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
