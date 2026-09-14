package com.linroid.kiff.binary

import com.linroid.kiff.algorithm.BinaryDiffAlgorithm

class BinaryDiffEngine(private val algorithm: BinaryDiffAlgorithm) {

  fun generatePatch(source: ByteArray, target: ByteArray): BinaryPatch {
    return algorithm.diff(source, target)
  }

  fun applyPatch(source: ByteArray, patch: BinaryPatch): ByteArray {
    val result = ByteArray(patch.newSize)
    var resultPos = 0
    var sourcePos = 0
    var diffPos = 0
    var extraPos = 0

    for (block in patch.controlBlocks) {
      // Apply diff bytes: add source bytes and diff bytes together
      for (i in 0 until block.diffLength) {
        val sourceByte = if (sourcePos + i < source.size) {
          source[sourcePos + i]
        } else {
          0
        }
        result[resultPos + i] =
          (sourceByte + patch.diffBytes[diffPos + i]).toByte()
      }
      resultPos += block.diffLength
      sourcePos += block.diffLength
      diffPos += block.diffLength

      // Copy extra bytes directly
      patch.extraBytes.copyInto(
        result,
        resultPos,
        extraPos,
        extraPos + block.extraLength,
      )
      resultPos += block.extraLength
      extraPos += block.extraLength

      // Seek in source
      sourcePos += block.oldSeek
    }
    return result
  }
}
