package com.linroid.kiff.algorithm

import com.linroid.kiff.core.Edit

class MyersDiffAlgorithm : DiffAlgorithm {
  override val name = "myers"

  override fun diff(source: List<String>, target: List<String>): List<Edit> {
    val n = source.size
    val m = target.size
    if (n == 0 && m == 0) return emptyList()
    if (n == 0) return listOf(Edit.Insert(0, target.toList()))
    if (m == 0) return listOf(Edit.Delete(0, n))

    val trace = findTrace(source, target, n, m)
    val ops = backtrack(trace, source, target, n, m)
    return groupEdits(ops, source, target)
  }

  private fun findTrace(
    source: List<String>,
    target: List<String>,
    n: Int,
    m: Int,
  ): List<Map<Int, Int>> {
    val max = n + m
    val v = IntArray(2 * max + 1)
    val trace = mutableListOf<Map<Int, Int>>()

    for (d in 0..max) {
      val snapshot = mutableMapOf<Int, Int>()
      for (k in -d..d step 2) {
        var x = if (k == -d || (k != d && v[k - 1 + max] < v[k + 1 + max])) {
          v[k + 1 + max]
        } else {
          v[k - 1 + max] + 1
        }
        var y = x - k
        while (x < n && y < m && source[x] == target[y]) {
          x++
          y++
        }
        v[k + max] = x
        snapshot[k] = x
        if (x >= n && y >= m) {
          trace.add(snapshot)
          return trace
        }
      }
      trace.add(snapshot)
    }
    return trace
  }

  private fun backtrack(
    trace: List<Map<Int, Int>>,
    source: List<String>,
    target: List<String>,
    n: Int,
    m: Int,
  ): List<OpType> {
    val ops = mutableListOf<OpType>()
    var x = n
    var y = m

    for (d in trace.size - 1 downTo 1) {
      val snapshot = trace[d - 1]
      val k = x - y

      val prevK = if (k == -d || (k != d && snapshot.getValue(k - 1) < snapshot.getValue(k + 1))) {
        k + 1
      } else {
        k - 1
      }
      val prevX = snapshot.getValue(prevK)
      val prevY = prevX - prevK

      // Diagonal (equal) moves
      while (x > prevX && y > prevY) {
        ops.add(OpType.EQUAL)
        x--
        y--
      }

      if (d > 0) {
        if (k == prevK + 1) {
          ops.add(OpType.DELETE)
          x--
        } else {
          ops.add(OpType.INSERT)
          y--
        }
      }
    }

    // Handle remaining diagonal at d=0
    while (x > 0 && y > 0) {
      ops.add(OpType.EQUAL)
      x--
      y--
    }

    ops.reverse()
    return ops
  }

  private fun groupEdits(
    ops: List<OpType>,
    source: List<String>,
    target: List<String>,
  ): List<Edit> {
    val edits = mutableListOf<Edit>()
    var sourceIndex = 0
    var targetIndex = 0
    var i = 0

    while (i < ops.size) {
      when (ops[i]) {
        OpType.EQUAL -> {
          val start = sourceIndex
          while (i < ops.size && ops[i] == OpType.EQUAL) {
            sourceIndex++
            targetIndex++
            i++
          }
          edits.add(Edit.Equal(start, sourceIndex - start))
        }
        OpType.DELETE -> {
          val start = sourceIndex
          while (i < ops.size && ops[i] == OpType.DELETE) {
            sourceIndex++
            i++
          }
          edits.add(Edit.Delete(start, sourceIndex - start))
        }
        OpType.INSERT -> {
          val position = sourceIndex
          val lines = mutableListOf<String>()
          while (i < ops.size && ops[i] == OpType.INSERT) {
            lines.add(target[targetIndex])
            targetIndex++
            i++
          }
          edits.add(Edit.Insert(position, lines))
        }
      }
    }
    return edits
  }

  private enum class OpType { EQUAL, DELETE, INSERT }
}
