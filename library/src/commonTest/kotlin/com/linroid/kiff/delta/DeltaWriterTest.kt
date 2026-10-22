package com.linroid.kiff.delta

import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.io.ByteArrayRestoreTarget
import com.linroid.kiff.io.asSource
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DeltaWriterTest {

  private val source = structuredBytes(16 * 1024, seed = 41)
  private val threshold = DeltaOp.MIN_EQUAL_IN_DIFF

  @Test
  fun aLongStretchThatEqualsTheSourceBecomesACopy() {
    val target = edited(2_000, 7_000)
    val written = writeDiff(target, 1_000, 10_000)
    assertEquals(
      listOf(
        Op(DeltaOp.DIFF, 1_001, 1_000),
        Op(DeltaOp.COPY, 4_999, 2_001),
        Op(DeltaOp.DIFF, 1, 7_000),
        Op(DeltaOp.COPY, 2_999, 7_001)
      ),
      written.ops
    )
    // Only what stayed a DIFF reaches the content stream: a short equal stretch and two edits.
    assertEquals(1_002, written.literals.size)
    assertEquals(2, written.literals.count { it != 0.toByte() })
    assertContentEquals(target.copyOfRange(1_000, 10_000), written.restore())
  }

  @Test
  fun aStretchOneByteShortOfTheThresholdStaysInTheDiff() {
    val target = edited(4_000, 4_000 + threshold)
    val written = writeDiff(target, 4_000, 4_001 + threshold)
    assertEquals(listOf(Op(DeltaOp.DIFF, threshold + 1L, 4_000)), written.ops)
    assertEquals(threshold + 1, written.literals.size)
    assertContentEquals(target.copyOfRange(4_000, 4_001 + threshold), written.restore())
  }

  @Test
  fun aStretchOfExactlyTheThresholdIsSplitOut() {
    val target = edited(4_000, 4_001 + threshold)
    val written = writeDiff(target, 4_000, 4_002 + threshold)
    assertEquals(
      listOf(
        Op(DeltaOp.DIFF, 1, 4_000),
        Op(DeltaOp.COPY, threshold.toLong(), 4_001),
        Op(DeltaOp.DIFF, 1, 4_001L + threshold)
      ),
      written.ops
    )
    assertEquals(2, written.literals.size)
    assertContentEquals(target.copyOfRange(4_000, 4_002 + threshold), written.restore())
  }

  @Test
  fun aStretchAtEitherEndIsSplitOutToo() {
    val target = edited(6_000)
    val written = writeDiff(target, 3_000, 9_000)
    assertEquals(
      listOf(
        Op(DeltaOp.COPY, 3_000, 3_000),
        Op(DeltaOp.DIFF, 1, 6_000),
        Op(DeltaOp.COPY, 2_999, 6_001)
      ),
      written.ops
    )
    assertContentEquals(target.copyOfRange(3_000, 9_000), written.restore())
  }

  @Test
  fun aDiffThatEqualsTheSourceThroughoutIsOneCopy() {
    val written = writeDiff(source, 500, 500 + threshold)
    assertEquals(listOf(Op(DeltaOp.COPY, threshold.toLong(), 500)), written.ops)
    assertEquals(0, written.literals.size)

    val short = writeDiff(source, 500, 499 + threshold)
    assertEquals(listOf(Op(DeltaOp.DIFF, threshold - 1L, 500)), short.ops)
  }

  @Test
  fun aDiffAgainstAnotherPartOfTheSourceKeepsItsOffsets() {
    // The target runs parallel to the source 3,000 bytes further on, as after a deletion.
    val target = source.copyOfRange(3_000, source.size)
    target[2_000] = (target[2_000] + 1).toByte()
    val sink = Written(source)
    sink.writer.diff(3_000 + 500, target, 500, 6_000)
    assertEquals(
      listOf(
        Op(DeltaOp.COPY, 1_500, 3_500),
        Op(DeltaOp.DIFF, 1, 5_000),
        Op(DeltaOp.COPY, 3_999, 5_001)
      ),
      sink.ops
    )
    assertContentEquals(target.copyOfRange(500, 6_000), sink.restore())
  }

  /** [source] with the bytes at [positions] changed. */
  private fun edited(vararg positions: Int): ByteArray = source.copyOf().also { target ->
    for (at in positions) target[at] = (target[at] + 1).toByte()
  }

  /** `target[from, to)` written as a DIFF against the same offsets of [source]. */
  private fun writeDiff(target: ByteArray, from: Int, to: Int): Written =
    Written(source).also { it.writer.diff(from.toLong(), target, from, to) }

  private data class Op(val opcode: Int, val length: Long, val sourceOffset: Long)

  private class Written(private val source: ByteArray) {
    private val content = ByteWriter(64)
    val writer = DeltaWriter(source, content)
    private val instructions by lazy { writer.finishInstructions() }

    val literals: ByteArray get() = content.toByteArray()

    /** The instructions, with each source offset resolved against the cursor. */
    val ops: List<Op>
      get() {
        val reader = ByteReader(instructions)
        var cursor = 0L
        return buildList {
          while (true) {
            val tag = reader.readVarLong()
            val opcode = (tag and DeltaOp.MASK).toInt()
            if (opcode == DeltaOp.END) break
            val length = tag ushr DeltaOp.SHIFT
            check(opcode == DeltaOp.COPY || opcode == DeltaOp.DIFF) { "opcode $opcode" }
            val offset = cursor + reader.readSignedVarLong()
            cursor = offset + length
            add(Op(opcode, length, offset))
          }
        }
      }

    fun restore(): ByteArray {
      val length = writer.length.toInt()
      val out = ByteArrayRestoreTarget(length)
      val consumed = DeltaReader()
        .apply(source.asSource(), ByteReader(instructions), literals, 0, out, length)
      assertEquals(literals.size, consumed, "content left unread")
      return out.toByteArray()
    }
  }
}
