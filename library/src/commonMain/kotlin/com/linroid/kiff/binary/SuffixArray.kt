package com.linroid.kiff.binary

/**
 * Pure Kotlin qsufsort implementation for building suffix arrays.
 * Based on Larsson-Sadakane algorithm, O(n log n) time.
 */
object SuffixArray {

  fun build(data: ByteArray): IntArray {
    val n = data.size
    if (n == 0) return IntArray(0)

    val sa = IntArray(n + 1)
    val rank = IntArray(n + 1)
    val tmp = IntArray(n + 1)

    // Initial ranking based on byte values
    for (i in 0 until n) {
      rank[i] = data[i].toInt() and 0xFF
    }
    rank[n] = -1

    for (i in 0..n) {
      sa[i] = i
    }

    var h = 1
    while (true) {
      // Sort by (rank[i], rank[i+h])
      val currentH = h
      val currentRank = rank.copyOf()

      // Insertion sort for simplicity; for large arrays a radix sort
      // would be faster, but this is sufficient for correctness.
      sortSuffixes(sa, currentRank, currentH, n)

      // Compute new ranks
      tmp[sa[0]] = 0
      for (i in 1..n) {
        tmp[sa[i]] = tmp[sa[i - 1]] +
          if (compareSuffix(currentRank, sa[i - 1], sa[i], currentH, n) != 0) {
            1
          } else {
            0
          }
      }
      for (i in 0..n) {
        rank[i] = tmp[i]
      }

      // If all ranks are unique, we're done
      if (rank[sa[n]] == n) break

      h *= 2
    }

    // Return SA without the sentinel (index n)
    val result = IntArray(n)
    var pos = 0
    for (i in 0..n) {
      if (sa[i] < n) {
        result[pos++] = sa[i]
      }
    }
    return result
  }

  private fun compareSuffix(
    rank: IntArray,
    a: Int,
    b: Int,
    h: Int,
    n: Int,
  ): Int {
    if (rank[a] != rank[b]) return rank[a] - rank[b]
    val ra = if (a + h <= n) rank[a + h] else -1
    val rb = if (b + h <= n) rank[b + h] else -1
    return ra - rb
  }

  private fun sortSuffixes(
    sa: IntArray,
    rank: IntArray,
    h: Int,
    n: Int,
  ) {
    // Simple merge sort for stability and O(n log n) guarantee
    val aux = IntArray(n + 1)
    mergeSort(sa, aux, rank, h, n, 0, n)
  }

  private fun mergeSort(
    sa: IntArray,
    aux: IntArray,
    rank: IntArray,
    h: Int,
    n: Int,
    lo: Int,
    hi: Int,
  ) {
    if (lo >= hi) return
    val mid = (lo + hi) / 2
    mergeSort(sa, aux, rank, h, n, lo, mid)
    mergeSort(sa, aux, rank, h, n, mid + 1, hi)
    merge(sa, aux, rank, h, n, lo, mid, hi)
  }

  private fun merge(
    sa: IntArray,
    aux: IntArray,
    rank: IntArray,
    h: Int,
    n: Int,
    lo: Int,
    mid: Int,
    hi: Int,
  ) {
    for (k in lo..hi) aux[k] = sa[k]
    var i = lo
    var j = mid + 1
    for (k in lo..hi) {
      when {
        i > mid -> sa[k] = aux[j++]
        j > hi -> sa[k] = aux[i++]
        compareSuffix(rank, aux[i], aux[j], h, n) <= 0 -> sa[k] = aux[i++]
        else -> sa[k] = aux[j++]
      }
    }
  }
}
