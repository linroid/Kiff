package com.linroid.kiff.core

import com.linroid.kiff.algorithm.DiffAlgorithm

class DiffEngine(private val algorithm: DiffAlgorithm) {

  fun generatePatch(source: List<String>, target: List<String>): Patch {
    val edits = algorithm.diff(source, target)
    return Patch(edits)
  }

  fun applyPatch(source: List<String>, patch: Patch): List<String> {
    val result = mutableListOf<String>()
    var sourceIndex = 0

    for (edit in patch.edits) {
      when (edit) {
        is Edit.Equal -> {
          repeat(edit.count) {
            result.add(source[sourceIndex++])
          }
        }
        is Edit.Delete -> {
          sourceIndex += edit.count
        }
        is Edit.Insert -> {
          result.addAll(edit.lines)
        }
      }
    }
    return result
  }
}
