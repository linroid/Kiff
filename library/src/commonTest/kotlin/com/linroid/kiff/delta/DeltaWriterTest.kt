package com.linroid.kiff.delta

import com.linroid.kiff.asBytes
import com.linroid.kiff.assertRestores
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.io.ByteArrayRestoreTarget
import com.linroid.kiff.io.asSource
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeltaWriterTest {

  private data class Op(val opcode: Int, val length: Long, val sourceOffset: Long? = null)

  /** [source] with every eighth byte changed, and the bytes either side of [equal], but not [equal]. */
  private fun nearlyTheSame(source: ByteArray, equal: IntRange) = ByteArray(source.size) { i ->
    val changed = i !in equal && (i % 8 == 0 || i == equal.first - 1 || i == equal.last + 1)
    if (changed) (source[i] + 1).toByte() else source[i]
  }

  /** Writes [target] as one DIFF against [source], and reads back what the writer made of it. */
  private fun writeDiff(source: ByteArray, target: ByteArray): Pair<List<Op>, ByteArray> {
    val literals = ByteWriter()
    val writer = DeltaWriter(source, literals)
    writer.diff(0, target, 0, target.size)
    val instructions = writer.finishInstructions()
    assertEquals(target.size.toLong(), writer.length)

    val restored = ByteArrayRestoreTarget(target.size)
    val content = literals.toByteArray()
    val consumed = DeltaReader().apply(
      source.asSource(), ByteReader(instructions), content, 0, restored, target.size
    )
    assertContentEquals(target, restored.toByteArray(), "restored bytes differ")
    assertEquals(content.size, consumed)
    return ops(instructions) to content
  }

  private fun ops(instructions: ByteArray): List<Op> = buildList {
    val reader = ByteReader(instructions)
    var sourceCursor = 0L
    while (true) {
      val tag = reader.readVarLong()
      val opcode = (tag and DeltaOp.MASK).toInt()
      if (opcode == DeltaOp.END) break
      val length = tag ushr DeltaOp.SHIFT
      val offset = when (opcode) {
        DeltaOp.COPY, DeltaOp.DIFF -> sourceCursor + reader.readSignedVarLong()
        else -> null
      }
      if (offset != null) sourceCursor = offset + length
      add(Op(opcode, length, offset))
    }
  }

  @Test
  fun aLongEqualStretchInADiffIsCopied() {
    val source = structuredBytes(16 * 1024, seed = 41)
    val target = nearlyTheSame(source, 4096 until 12 * 1024)
    val (ops, content) = writeDiff(source, target)
    assertEquals(
      listOf(
        Op(DeltaOp.DIFF, 4096, 0),
        Op(DeltaOp.COPY, 8 * 1024L, 4096),
        Op(DeltaOp.DIFF, 4096, 12 * 1024L)
      ),
      ops
    )
    assertEquals(8 * 1024, content.size, "only the bytes either side carry differences")
  }

  @Test
  fun anEqualStretchOfExactlyTheThresholdIsCopied() {
    val source = structuredBytes(4096, seed = 42)
    val equal = 1024 until 1024 + DeltaWriter.EQUAL_TO_COPY
    val (ops, content) = writeDiff(source, nearlyTheSame(source, equal))
    assertEquals(listOf(DeltaOp.DIFF, DeltaOp.COPY, DeltaOp.DIFF), ops.map { it.opcode })
    assertEquals(DeltaWriter.EQUAL_TO_COPY.toLong(), ops[1].length)
    assertEquals(4096 - DeltaWriter.EQUAL_TO_COPY, content.size)
  }

  @Test
  fun aShorterEqualStretchStaysInTheDiff() {
    // Its zeroes pack to less than the two instructions splitting around it would cost.
    val source = structuredBytes(4096, seed = 43)
    val equal = 1024 until 1024 + DeltaWriter.EQUAL_TO_COPY - 1
    val (ops, content) = writeDiff(source, nearlyTheSame(source, equal))
    assertEquals(listOf(Op(DeltaOp.DIFF, 4096, 0)), ops)
    assertEquals(4096, content.size)
  }

  @Test
  fun aDiffThatEqualsItsSourceThroughoutIsOneCopy() {
    val source = structuredBytes(8 * 1024, seed = 44)
    val (ops, content) = writeDiff(source, source.copyOf())
    assertEquals(listOf(Op(DeltaOp.COPY, 8 * 1024L, 0)), ops)
    assertEquals(0, content.size)
  }

  @Test
  fun equalStretchesAtEitherEndAreCopiedToo() {
    val source = structuredBytes(8 * 1024, seed = 45)
    val target = source.copyOf()
    for (i in 2048 until 6 * 1024 step 8) target[i] = (target[i] + 1).toByte()
    val (ops, content) = writeDiff(source, target)
    assertEquals(
      listOf(
        Op(DeltaOp.COPY, 2048, 0),
        Op(DeltaOp.DIFF, 4089, 2048),
        Op(DeltaOp.COPY, 2055, 6137)
      ),
      ops
    )
    assertEquals(4089, content.size)
  }

  @Test
  fun aPatchCarriesOnlyTheDifferencesAroundAnEqualStretch() {
    // The scanner runs one DIFF over all of it: every chunk agrees, the equal ones best of all.
    val source = structuredBytes(256 * 1024, seed = 46)
    val equal = 64 * 1024 until 192 * 1024
    val target = nearlyTheSame(source, equal)
    val patcher = asBytes()
    assertRestores(patcher, source, target)
    val content = patcher.explain(source, target).literalBytes
    assertTrue(
      content <= target.size - (equal.last - equal.first + 1),
      "expected the equal ${equal.last - equal.first + 1} bytes to be copied, carried $content"
    )
  }
}
