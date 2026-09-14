package com.linroid.kiff.core

import com.linroid.kiff.algorithm.DiffAlgorithm
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffEngineTest {

  private val simpleAlgorithm = object : DiffAlgorithm {
    override val name = "simple-test"

    override fun diff(source: List<String>, target: List<String>): List<Edit> {
      val edits = mutableListOf<Edit>()
      var prefixLen = 0
      while (
        prefixLen < source.size &&
        prefixLen < target.size &&
        source[prefixLen] == target[prefixLen]
      ) {
        prefixLen++
      }
      var suffixLen = 0
      while (
        suffixLen < source.size - prefixLen &&
        suffixLen < target.size - prefixLen &&
        source[source.size - 1 - suffixLen] == target[target.size - 1 - suffixLen]
      ) {
        suffixLen++
      }

      if (prefixLen > 0) {
        edits.add(Edit.Equal(0, prefixLen))
      }
      val deletedCount = source.size - prefixLen - suffixLen
      if (deletedCount > 0) {
        edits.add(Edit.Delete(prefixLen, deletedCount))
      }
      val insertedLines = target.subList(prefixLen, target.size - suffixLen)
      if (insertedLines.isNotEmpty()) {
        edits.add(Edit.Insert(prefixLen, insertedLines))
      }
      if (suffixLen > 0) {
        edits.add(Edit.Equal(source.size - suffixLen, suffixLen))
      }
      return edits
    }
  }

  @Test
  fun identicalInputsProduceNoChanges() {
    val engine = DiffEngine(simpleAlgorithm)
    val lines = listOf("a", "b", "c")
    val patch = engine.generatePatch(lines, lines)

    assertEquals(lines, engine.applyPatch(lines, patch))
  }

  @Test
  fun applyPatchRecoversTarget() {
    val engine = DiffEngine(simpleAlgorithm)
    val source = listOf("line1", "line2", "line3")
    val target = listOf("line1", "modified", "line3")

    val patch = engine.generatePatch(source, target)
    val recovered = engine.applyPatch(source, patch)

    assertEquals(target, recovered)
  }

  @Test
  fun insertOnlyPatch() {
    val engine = DiffEngine(simpleAlgorithm)
    val source = listOf("a", "b")
    val target = listOf("a", "b", "c", "d")

    val patch = engine.generatePatch(source, target)
    assertEquals(target, engine.applyPatch(source, patch))
  }

  @Test
  fun deleteOnlyPatch() {
    val engine = DiffEngine(simpleAlgorithm)
    val source = listOf("a", "b", "c", "d")
    val target = listOf("a", "b")

    val patch = engine.generatePatch(source, target)
    assertEquals(target, engine.applyPatch(source, patch))
  }

  @Test
  fun emptySourceToNonEmpty() {
    val engine = DiffEngine(simpleAlgorithm)
    val source = emptyList<String>()
    val target = listOf("new1", "new2")

    val patch = engine.generatePatch(source, target)
    assertEquals(target, engine.applyPatch(source, patch))
  }

  @Test
  fun nonEmptySourceToEmpty() {
    val engine = DiffEngine(simpleAlgorithm)
    val source = listOf("old1", "old2")
    val target = emptyList<String>()

    val patch = engine.generatePatch(source, target)
    assertEquals(target, engine.applyPatch(source, patch))
  }
}
