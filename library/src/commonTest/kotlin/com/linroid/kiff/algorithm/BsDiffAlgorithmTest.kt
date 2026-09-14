package com.linroid.kiff.algorithm

import com.linroid.kiff.binary.BinaryDiffEngine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BsDiffAlgorithmTest {

  private val algorithm = BsDiffAlgorithm()
  private val engine = BinaryDiffEngine(algorithm)

  private fun roundtrip(source: ByteArray, target: ByteArray) {
    val patch = engine.generatePatch(source, target)
    assertEquals(target.size, patch.newSize)
    val recovered = engine.applyPatch(source, patch)
    assertContentEquals(
      target,
      recovered,
      "Roundtrip failed: source=${source.toList()}, target=${target.toList()}",
    )
  }

  @Test
  fun bothEmpty() {
    roundtrip(ByteArray(0), ByteArray(0))
  }

  @Test
  fun identicalInputs() {
    val data = byteArrayOf(1, 2, 3, 4, 5)
    roundtrip(data, data.copyOf())
  }

  @Test
  fun emptySourceToNonEmpty() {
    roundtrip(ByteArray(0), byteArrayOf(1, 2, 3))
  }

  @Test
  fun nonEmptyToEmpty() {
    roundtrip(byteArrayOf(1, 2, 3), ByteArray(0))
  }

  @Test
  fun singleByteChange() {
    val source = byteArrayOf(1, 2, 3, 4, 5)
    val target = byteArrayOf(1, 2, 99, 4, 5)
    roundtrip(source, target)
  }

  @Test
  fun insertionInMiddle() {
    val source = byteArrayOf(1, 2, 3, 4, 5)
    val target = byteArrayOf(1, 2, 3, 99, 98, 4, 5)
    roundtrip(source, target)
  }

  @Test
  fun deletionInMiddle() {
    val source = byteArrayOf(1, 2, 3, 4, 5)
    val target = byteArrayOf(1, 2, 5)
    roundtrip(source, target)
  }

  @Test
  fun completelyDifferent() {
    val source = byteArrayOf(1, 2, 3, 4, 5)
    val target = byteArrayOf(10, 20, 30, 40, 50, 60)
    roundtrip(source, target)
  }

  @Test
  fun singleByteSources() {
    roundtrip(byteArrayOf(0), byteArrayOf(1))
    roundtrip(byteArrayOf(1), byteArrayOf(1))
  }

  @Test
  fun largerPayload() {
    val source = ByteArray(1024) { (it % 256).toByte() }
    val target = source.copyOf()
    // Modify a few bytes
    target[100] = 0
    target[500] = 127
    target[999] = -1
    roundtrip(source, target)
  }

  @Test
  fun largerPayloadWithInsertion() {
    val source = ByteArray(1024) { (it % 256).toByte() }
    val target = ByteArray(1100) { i ->
      if (i < 512) source[i]
      else if (i < 588) (i % 73).toByte()
      else source[i - 76]
    }
    roundtrip(source, target)
  }

  @Test
  fun repeatedPattern() {
    val source = ByteArray(100) { (it % 4).toByte() }
    val target = ByteArray(100) { ((it + 1) % 4).toByte() }
    roundtrip(source, target)
  }

  @Test
  fun appendBytes() {
    val source = byteArrayOf(1, 2, 3)
    val target = byteArrayOf(1, 2, 3, 4, 5, 6)
    roundtrip(source, target)
  }

  @Test
  fun prependBytes() {
    val source = byteArrayOf(4, 5, 6)
    val target = byteArrayOf(1, 2, 3, 4, 5, 6)
    roundtrip(source, target)
  }
}
