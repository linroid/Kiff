package com.linroid.kiff.binary

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class BinaryDiffEngineTest {

  private val engine = BinaryDiffEngine(
    object : com.linroid.kiff.algorithm.BinaryDiffAlgorithm {
      override val name = "test"
      override fun diff(
        source: ByteArray,
        target: ByteArray,
      ): BinaryPatch {
        error("Not used in applyPatch tests")
      }
    },
  )

  @Test
  fun applyPatchWithSingleDiffBlock() {
    val source = byteArrayOf(1, 2, 3, 4, 5)
    val patch = BinaryPatch(
      controlBlocks = listOf(ControlBlock(diffLength = 5, extraLength = 0, oldSeek = 0)),
      diffBytes = byteArrayOf(0, 0, 0, 0, 10), // last byte changes by +10
      extraBytes = ByteArray(0),
      newSize = 5,
    )
    val result = engine.applyPatch(source, patch)
    assertContentEquals(byteArrayOf(1, 2, 3, 4, 15), result)
  }

  @Test
  fun applyPatchWithExtraBytesOnly() {
    val source = ByteArray(0)
    val patch = BinaryPatch(
      controlBlocks = listOf(ControlBlock(diffLength = 0, extraLength = 3, oldSeek = 0)),
      diffBytes = ByteArray(0),
      extraBytes = byteArrayOf(10, 20, 30),
      newSize = 3,
    )
    val result = engine.applyPatch(source, patch)
    assertContentEquals(byteArrayOf(10, 20, 30), result)
  }

  @Test
  fun applyPatchWithDiffAndExtra() {
    val source = byteArrayOf(1, 2, 3)
    val patch = BinaryPatch(
      controlBlocks = listOf(
        ControlBlock(diffLength = 2, extraLength = 1, oldSeek = 1),
      ),
      diffBytes = byteArrayOf(0, 5), // first byte same, second changes by +5
      extraBytes = byteArrayOf(99), // one extra byte
      newSize = 3,
    )
    val result = engine.applyPatch(source, patch)
    assertContentEquals(byteArrayOf(1, 7, 99), result)
  }

  @Test
  fun applyPatchWithMultipleControlBlocks() {
    val source = byteArrayOf(10, 20, 30, 40, 50)
    val patch = BinaryPatch(
      controlBlocks = listOf(
        ControlBlock(diffLength = 2, extraLength = 1, oldSeek = 2),
        ControlBlock(diffLength = 1, extraLength = 0, oldSeek = 0),
      ),
      diffBytes = byteArrayOf(0, 0, 0), // no changes in diff
      extraBytes = byteArrayOf(77),
      newSize = 4,
    )
    // Block 1: copy source[0..1] with diff → 10, 20; extra → 77; seek +2 → sourcePos=4
    // Block 2: copy source[4] with diff → 50
    val result = engine.applyPatch(source, patch)
    assertContentEquals(byteArrayOf(10, 20, 77, 50), result)
  }

  @Test
  fun applyPatchEmptyPatch() {
    val source = byteArrayOf(1, 2, 3)
    val patch = BinaryPatch(
      controlBlocks = emptyList(),
      diffBytes = ByteArray(0),
      extraBytes = ByteArray(0),
      newSize = 0,
    )
    val result = engine.applyPatch(source, patch)
    assertEquals(0, result.size)
  }

  @Test
  fun applyPatchSourceBeyondBoundsUsesZero() {
    val source = byteArrayOf(1, 2)
    val patch = BinaryPatch(
      controlBlocks = listOf(
        ControlBlock(diffLength = 4, extraLength = 0, oldSeek = 0),
      ),
      diffBytes = byteArrayOf(0, 0, 10, 20),
      extraBytes = ByteArray(0),
      newSize = 4,
    )
    // source[0]=1, source[1]=2, source[2]=0(oob), source[3]=0(oob)
    val result = engine.applyPatch(source, patch)
    assertContentEquals(byteArrayOf(1, 2, 10, 20), result)
  }
}
