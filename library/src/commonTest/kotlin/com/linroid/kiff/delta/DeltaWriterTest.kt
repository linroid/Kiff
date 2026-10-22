package com.linroid.kiff.delta

import com.linroid.kiff.Kiff
import com.linroid.kiff.asBytes
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.RegionNode
import com.linroid.kiff.io.ByteArrayRestoreTarget
import com.linroid.kiff.io.ByteArraySource
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A DIFF carries one content byte per target byte, and the reader unpacks and holds every one.
 * Where a long stretch of it equals the source exactly, the writer copies that stretch instead.
 */
class DeltaWriterTest {

  private val source = Random(17).nextBytes(12 * 1024)

  @Test
  fun copiesAnExactStretchInsideADiff() {
    val target = differingExcept(4096 until 8192)
    val written = write { it.diff(0, target, 0, target.size) }
    assertEquals(listOf(diff(4096), copy(4096), diff(4096)), written.ops)
    assertEquals(8192, written.literals.size, "the copied stretch still reached the content")
    assertContentEquals(target, restore(written, target.size))
  }

  @Test
  fun keepsAShorterExactStretchInTheDiff() {
    val short = DeltaOp.MIN_COPY_IN_DIFF - 1
    val kept = differingExcept(4096 until 4096 + short)
    val written = write { it.diff(0, kept, 0, kept.size) }
    assertEquals(listOf(diff(kept.size)), written.ops)
    assertContentEquals(kept, restore(written, kept.size))

    val long = DeltaOp.MIN_COPY_IN_DIFF
    val split = differingExcept(4096 until 4096 + long)
    val copied = write { it.diff(0, split, 0, split.size) }
    assertEquals(listOf(diff(4096), copy(long), diff(split.size - 4096 - long)), copied.ops)
    assertContentEquals(split, restore(copied, split.size))
  }

  @Test
  fun copiesExactStretchesAtEitherEndWithoutEmptyDiffs() {
    val target = differingExcept(0 until 2048, 10_240 until 12_288)
    val written = write { it.diff(0, target, 0, target.size) }
    assertEquals(listOf(copy(2048), diff(8192), copy(2048)), written.ops)
    assertContentEquals(target, restore(written, target.size))

    val whole = write { it.diff(0, source, 0, source.size) }
    assertEquals(listOf(copy(source.size)), whole.ops)
    assertEquals(0, whole.literals.size)
  }

  @Test
  fun copiesFromWhereTheDiffReadsTheSource() {
    // The region sits 300 bytes into the target and reads the source from 1000 on.
    val region = ByteArray(4096) { (source[1000 + it] + 1).toByte() }
    source.copyInto(region, 1024, 1000 + 1024, 1000 + 3072)
    val target = ByteArray(300) { 7 } + region
    val written = write {
      it.add(target, 0, 300)
      it.diff(1000, target, 300, target.size)
    }
    assertEquals(
      listOf(Op(DeltaOp.ADD, 300), diff(1024, sourceMove = 1000), copy(2048), diff(1024)),
      written.ops
    )
    assertContentEquals(target, restore(written, target.size))
  }

  @Test
  fun theScannersDiffAcrossAnExactStretchBecomesACopy() {
    // Offsets that moved change every eighth byte, which keeps the whole target one DIFF to the
    // scanner, except in the middle third: the source exactly, from 4089 up to 8192.
    val target = source.copyOf()
    for (i in target.indices step 8) if (i !in 4096 until 8192) target[i]++
    val literals = ByteWriter()
    val node = encodeWhole(
      ByteArraySource(source),
      ByteArraySource(target),
      RollingHashAlgorithm,
      literals
    )
    assertIs<RegionNode.Delta>(node)
    assertEquals(listOf(diff(4089), copy(4103), diff(4096)), ops(node.instructions))
    assertEquals(4089 + 4096, literals.size)

    val patch = asBytes().createPatch(source, target)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  private data class Op(val opcode: Int, val length: Long, val sourceMove: Long = 0)

  /** What a [DeltaWriter] wrote for one region: its instructions and its content. */
  private class Written(val instructions: ByteArray, val literals: ByteArray) {
    val ops: List<Op> get() = ops(instructions)
  }

  /** [source] with every byte changed except in [equal]. */
  private fun differingExcept(vararg equal: IntRange): ByteArray =
    ByteArray(source.size) { i ->
      if (equal.any { i in it }) source[i] else (source[i] + 1).toByte()
    }

  private fun write(describe: (DeltaWriter) -> Unit): Written {
    val literals = ByteWriter()
    val writer = DeltaWriter(source, literals)
    describe(writer)
    return Written(writer.finishInstructions(), literals.toByteArray())
  }

  private fun restore(written: Written, length: Int): ByteArray {
    val out = ByteArrayRestoreTarget(length)
    val used = DeltaReader().apply(
      ByteArraySource(source),
      ByteReader(written.instructions),
      written.literals,
      0,
      out,
      length
    )
    assertEquals(written.literals.size, used, "the delta left content unread")
    return out.toByteArray()
  }

  private fun diff(length: Int, sourceMove: Long = 0) =
    Op(DeltaOp.DIFF, length.toLong(), sourceMove)

  private fun copy(length: Int) = Op(DeltaOp.COPY, length.toLong())

  private companion object {
    fun ops(instructions: ByteArray): List<Op> {
      val reader = ByteReader(instructions)
      val ops = mutableListOf<Op>()
      while (true) {
        val tag = reader.readVarLong()
        val opcode = (tag and DeltaOp.MASK).toInt()
        val length = tag ushr DeltaOp.SHIFT
        when (opcode) {
          DeltaOp.END -> return ops
          DeltaOp.COPY, DeltaOp.DIFF -> ops += Op(opcode, length, reader.readSignedVarLong())
          DeltaOp.RUN -> ops += Op(opcode, length).also { reader.readByte() }
          else -> ops += Op(opcode, length)
        }
      }
    }
  }
}
