package com.linroid.kiff.algorithm

import com.linroid.kiff.binary.BinaryPatch

interface BinaryDiffAlgorithm {
  val name: String
  fun diff(source: ByteArray, target: ByteArray): BinaryPatch
}
