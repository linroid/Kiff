package com.linroid.kiff.algorithm

import com.linroid.kiff.binary.BinaryPatch
import com.linroid.kiff.binary.ControlBlock
import com.linroid.kiff.binary.SuffixArray

class BsDiffAlgorithm : BinaryDiffAlgorithm {

  override val name: String = "bsdiff"

  override fun diff(source: ByteArray, target: ByteArray): BinaryPatch {
    if (target.isEmpty()) {
      return BinaryPatch(emptyList(), ByteArray(0), ByteArray(0), 0)
    }
    if (source.isEmpty()) {
      return BinaryPatch(
        controlBlocks = listOf(
          ControlBlock(diffLength = 0, extraLength = target.size, oldSeek = 0),
        ),
        diffBytes = ByteArray(0),
        extraBytes = target.copyOf(),
        newSize = target.size,
      )
    }

    val sa = SuffixArray.build(source)
    val controlBlocks = mutableListOf<ControlBlock>()
    val diffStream = mutableListOf<Byte>()
    val extraStream = mutableListOf<Byte>()

    var scan = 0
    var lastScan = 0
    var lastPos = 0
    var lastOffset = 0
    var pos = 0

    while (scan < target.size) {
      var oldscore = 0
      scan += 1
      var scsc = scan

      while (scan < target.size) {
        val match = search(sa, source, target, scan)
        pos = match.first
        val len = match.second

        while (scsc < scan + len) {
          if (scsc + lastOffset < source.size &&
            scsc + lastOffset >= 0 &&
            source[scsc + lastOffset] == target[scsc]
          ) {
            oldscore++
          }
          scsc++
        }

        if ((len == oldscore && len != 0) || (len > oldscore + 8)) break
        if (scan + lastOffset < source.size &&
          scan + lastOffset >= 0 &&
          source[scan + lastOffset] == target[scan]
        ) {
          oldscore--
        }
        scan++
      }

      if (scan >= target.size && oldscore == 0) {
        // No more matches; emit remaining as extra
        break
      }

      // Count forward matches from lastScan
      var s = 0
      var sf = 0
      var lenF = 0
      for (i in 0 until scan - lastScan) {
        if (lastPos + i < source.size &&
          source[lastPos + i] == target[lastScan + i]
        ) {
          s++
        }
        s--
        if (s > sf) {
          sf = s
          lenF = i + 1
        }
      }

      // Count backward matches from scan
      var lenB = 0
      if (scan < target.size) {
        s = 0
        var sb = 0
        for (i in 1..(scan - lastScan)) {
          if (pos - i >= 0 &&
            source[pos - i] == target[scan - i]
          ) {
            s++
          }
          s--
          if (s > sb) {
            sb = s
            lenB = i
          }
        }
      }

      // Handle overlap
      if (lastScan + lenF > scan - lenB) {
        val overlap = (lastScan + lenF) - (scan - lenB)
        s = 0
        var ss = 0
        var lens = 0
        for (i in 0 until overlap) {
          val fwdMatch =
            if (lastPos + lenF - overlap + i < source.size &&
              source[lastPos + lenF - overlap + i] ==
              target[lastScan + lenF - overlap + i]
            ) {
              1
            } else {
              0
            }
          val bwdMatch =
            if (pos - lenB + i >= 0 &&
              source[pos - lenB + i] == target[scan - lenB + i]
            ) {
              1
            } else {
              0
            }
          s += fwdMatch - bwdMatch
          if (s > ss) {
            ss = s
            lens = i + 1
          }
        }
        lenF += lens - overlap
        lenB -= lens
      }

      // Emit diff bytes
      for (i in 0 until lenF) {
        diffStream.add(
          (target[lastScan + i] - source[lastPos + i]).toByte(),
        )
      }

      // Emit extra bytes (gap between forward and backward regions)
      val extraStart = lastScan + lenF
      val extraEnd = scan - lenB
      for (i in extraStart until extraEnd) {
        extraStream.add(target[i])
      }

      controlBlocks.add(
        ControlBlock(
          diffLength = lenF,
          extraLength = extraEnd - extraStart,
          oldSeek = (pos - lenB) - (lastPos + lenF),
        ),
      )

      lastScan = scan - lenB
      lastPos = pos - lenB
      lastOffset = pos - scan
    }

    // Handle any remaining target bytes
    if (lastScan < target.size) {
      val remaining = target.size - lastScan
      // Emit diff bytes for what overlaps with source
      val diffLen = minOf(remaining, source.size - lastPos)
        .coerceAtLeast(0)
      for (i in 0 until diffLen) {
        val srcByte = if (lastPos + i < source.size) source[lastPos + i] else 0
        diffStream.add((target[lastScan + i] - srcByte).toByte())
      }
      // Emit extra bytes for the rest
      val extraLen = remaining - diffLen
      for (i in 0 until extraLen) {
        extraStream.add(target[lastScan + diffLen + i])
      }
      controlBlocks.add(
        ControlBlock(
          diffLength = diffLen,
          extraLength = extraLen,
          oldSeek = 0,
        ),
      )
    }

    return BinaryPatch(
      controlBlocks = controlBlocks,
      diffBytes = diffStream.toByteArray(),
      extraBytes = extraStream.toByteArray(),
      newSize = target.size,
    )
  }

  private fun search(
    sa: IntArray,
    source: ByteArray,
    target: ByteArray,
    targetStart: Int,
  ): Pair<Int, Int> {
    if (sa.isEmpty()) return Pair(0, 0)

    var lo = 0
    var hi = sa.size - 1
    var bestPos = sa[0]
    var bestLen = 0

    // Check boundaries and find best via binary search
    val loLen = matchLength(source, sa[0], target, targetStart)
    val hiLen = matchLength(source, sa[sa.size - 1], target, targetStart)

    if (loLen > bestLen) { bestLen = loLen; bestPos = sa[0] }
    if (hiLen > bestLen) { bestLen = hiLen; bestPos = sa[sa.size - 1] }

    while (hi - lo > 1) {
      val mid = lo + (hi - lo) / 2
      val midLen = matchLength(source, sa[mid], target, targetStart)
      if (midLen > bestLen) {
        bestLen = midLen
        bestPos = sa[mid]
      }

      // Compare at the current best match length to narrow the search
      val cmp = compareBytesAt(
        source, sa[mid], target, targetStart,
        minOf(loLen, hiLen),
      )
      if (cmp < 0) {
        lo = mid
        if (midLen > loLen) loLen.also { /* keep existing */ }
      } else {
        hi = mid
      }
    }

    return Pair(bestPos, bestLen)
  }

  private fun matchLength(
    source: ByteArray,
    sourceStart: Int,
    target: ByteArray,
    targetStart: Int,
  ): Int {
    val maxLen = minOf(source.size - sourceStart, target.size - targetStart)
    var len = 0
    while (len < maxLen &&
      source[sourceStart + len] == target[targetStart + len]
    ) {
      len++
    }
    return len
  }

  private fun compareBytesAt(
    source: ByteArray,
    sourceStart: Int,
    target: ByteArray,
    targetStart: Int,
    @Suppress("UNUSED_PARAMETER") hint: Int = 0,
  ): Int {
    val maxLen = minOf(source.size - sourceStart, target.size - targetStart)
    for (i in 0 until maxLen) {
      val a = source[sourceStart + i].toInt() and 0xFF
      val b = target[targetStart + i].toInt() and 0xFF
      if (a != b) return a - b
    }
    return (source.size - sourceStart) - (target.size - targetStart)
  }
}
