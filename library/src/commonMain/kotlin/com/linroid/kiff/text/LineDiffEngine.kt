package com.linroid.kiff.text

import com.linroid.kiff.text.LineDiffAlgorithm

class LineDiffEngine(private val algorithm: LineDiffAlgorithm) {

  fun generatePatch(source: List<String>, target: List<String>): LinePatch {
    val edits = algorithm.diff(source, target)
    return LinePatch(edits)
  }

  fun applyPatch(source: List<String>, patch: LinePatch): List<String> {
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
