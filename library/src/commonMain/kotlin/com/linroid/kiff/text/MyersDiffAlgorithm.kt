package com.linroid.kiff.text

/**
 * Myers' difference algorithm, in its linear-space form: a shortest edit script in O((N+M)D) time
 * and O(N+M) memory, where D is the number of lines deleted and inserted.
 *
 * The textbook form keeps the frontier of every step it takes so it can walk back along the path it
 * found, and that is memory in the square of D: a few thousand changed lines is gigabytes, which
 * a patch encoder meets on an ordinary regenerated text file. This one searches from both ends at
 * once until the two searches meet in the middle of a shortest path, then solves each half the same
 * way, so it only ever holds two frontiers.
 *
 * Within each run of changes, deletions come before insertions, which is the order unified diffs
 * print them in.
 */
class MyersDiffAlgorithm : LineDiffAlgorithm {
  override val name = "myers"

  override fun diff(source: List<String>, target: List<String>): List<Edit> =
    checkNotNull(Search(source, target).run(Int.MAX_VALUE)) { "an unbounded search always ends" }

  /** Gives up as soon as the search knows the script is longer than [maxEdits]. */
  override fun diffWithin(source: List<String>, target: List<String>, maxEdits: Int): List<Edit>? =
    Search(source, target).run(maxEdits)

  private class Search(source: List<String>, private val target: List<String>) {

    // Lines as small numbers, so comparing two costs the same however long they are.
    private val a: IntArray
    private val b: IntArray

    init {
      val ids = HashMap<String, Int>()
      a = IntArray(source.size) { ids.getOrPut(source[it]) { ids.size } }
      b = IntArray(target.size) { ids.getOrPut(target[it]) { ids.size } }
    }

    private val edits = ArrayList<Edit>()
    private var sourceAt = 0
    private var targetAt = 0
    private var equal = 0
    private var deleted = 0
    private var inserted = 0

    fun run(maxEdits: Int): List<Edit>? {
      if (!solve(0, a.size, 0, b.size, maxEdits)) return null
      flushEqual()
      flushChange()
      return edits
    }

    /**
     * Emits a shortest script for `a[aFrom, aTo)` against `b[bFrom, bTo)`, or answers false when
     * it would need more than [budget] edits.
     *
     * Only the outermost call has a budget. Once that search has met in the middle the whole cost
     * is known, and the halves below it cost no more than it together.
     */
    private fun solve(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, budget: Int): Boolean {
      var aStart = aFrom
      var bStart = bFrom
      while (aStart < aTo && bStart < bTo && a[aStart] == b[bStart]) {
        aStart++
        bStart++
      }
      var aEnd = aTo
      var bEnd = bTo
      while (aEnd > aStart && bEnd > bStart && a[aEnd - 1] == b[bEnd - 1]) {
        aEnd--
        bEnd--
      }
      val n = aEnd - aStart
      val m = bEnd - bStart
      when {
        n == 0 || m == 0 -> {
          if (n.toLong() + m > budget) return false
          equal(aStart - aFrom)
          delete(n)
          insert(m)
        }
        else -> {
          val middle = middle(aStart, aEnd, bStart, bEnd, budget) ?: return false
          equal(aStart - aFrom)
          solve(aStart, middle.x, bStart, middle.y, Int.MAX_VALUE)
          solve(middle.x, aEnd, middle.y, bEnd, Int.MAX_VALUE)
        }
      }
      equal(aTo - aEnd)
      return true
    }

    /**
     * A point on a shortest path through `a[aFrom, aTo)` and `b[bFrom, bTo)` that splits its edits
     * roughly in half, or null when that path is longer than [budget].
     *
     * Both ranges are non-empty and share no first or last line, which is what guarantees the point
     * is strictly inside the path, so each half is a smaller problem. The forward search runs from
     * the start and the reverse one from the end, a step each in turn, until one reaches a diagonal
     * the other has already gone past.
     */
    private fun middle(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int, budget: Int): Point? {
      val n = aTo - aFrom
      val m = bTo - bFrom
      val steps = (n + m + 1) / 2
      val offset = steps
      val size = 2 * steps + 2
      // How far along `a` each diagonal k (= x - y) has reached, from each end; -1 if not yet.
      val forward = IntArray(size) { -1 }
      val reverse = IntArray(size) { -1 }
      forward[offset + 1] = 0
      reverse[offset + 1] = 0
      val delta = n - m
      // With an odd difference the searches meet on a forward step, with an even one on a reverse.
      val odd = delta % 2 != 0
      // Diagonals that have run off the edge of the grid need no further steps.
      var forwardStart = 0
      var forwardEnd = 0
      var reverseStart = 0
      var reverseEnd = 0
      // A meeting at step d costs 2d - 1 edits or 2d, so a budget allows about half its size.
      val lastStep = minOf(steps - 1, budget / 2 + 1)

      for (d in 0..lastStep) {
        var k = -d + forwardStart
        while (k <= d - forwardEnd) {
          val at = offset + k
          var x = if (k == -d || (k != d && forward[at - 1] < forward[at + 1])) {
            forward[at + 1]
          } else {
            forward[at - 1] + 1
          }
          var y = x - k
          while (x < n && y < m && a[aFrom + x] == b[bFrom + y]) {
            x++
            y++
          }
          forward[at] = x
          if (x > n) {
            forwardEnd += 2
          } else if (y > m) {
            forwardStart += 2
          } else if (odd) {
            val opposite = offset + delta - k
            if (opposite in 0 until size && reverse[opposite] != -1 && x >= n - reverse[opposite]) {
              return if (2 * d - 1 > budget) null else Point(aFrom + x, bFrom + y)
            }
          }
          k += 2
        }

        k = -d + reverseStart
        while (k <= d - reverseEnd) {
          val at = offset + k
          var x = if (k == -d || (k != d && reverse[at - 1] < reverse[at + 1])) {
            reverse[at + 1]
          } else {
            reverse[at - 1] + 1
          }
          var y = x - k
          while (x < n && y < m && a[aTo - 1 - x] == b[bTo - 1 - y]) {
            x++
            y++
          }
          reverse[at] = x
          if (x > n) {
            reverseEnd += 2
          } else if (y > m) {
            reverseStart += 2
          } else if (!odd) {
            val opposite = offset + delta - k
            if (opposite in 0 until size && forward[opposite] != -1) {
              val forwardX = forward[opposite]
              if (forwardX >= n - x) {
                val forwardY = offset + forwardX - opposite
                return if (2 * d > budget) null else Point(aFrom + forwardX, bFrom + forwardY)
              }
            }
          }
          k += 2
        }
      }
      // The searches only fail to meet when the ranges have no line in common, and then the
      // shortest script deletes one side and inserts the other - or they ran out of budget first.
      if (lastStep < steps - 1 || n.toLong() + m > budget) return null
      return Point(aTo, bFrom)
    }

    private fun equal(count: Int) {
      if (count == 0) return
      flushChange()
      equal += count
    }

    private fun delete(count: Int) {
      if (count == 0) return
      flushEqual()
      deleted += count
    }

    private fun insert(count: Int) {
      if (count == 0) return
      flushEqual()
      inserted += count
    }

    private fun flushEqual() {
      if (equal == 0) return
      edits.add(Edit.Equal(sourceAt, equal))
      sourceAt += equal
      targetAt += equal
      equal = 0
    }

    private fun flushChange() {
      if (deleted > 0) {
        edits.add(Edit.Delete(sourceAt, deleted))
        sourceAt += deleted
        deleted = 0
      }
      if (inserted > 0) {
        edits.add(Edit.Insert(sourceAt, target.subList(targetAt, targetAt + inserted).toList()))
        targetAt += inserted
        inserted = 0
      }
    }
  }

  private class Point(val x: Int, val y: Int)
}
