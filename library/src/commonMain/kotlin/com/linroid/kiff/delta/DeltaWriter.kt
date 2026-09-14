package com.linroid.kiff.delta

import com.linroid.kiff.internal.ByteWriter
import com.linroid.kiff.internal.Lzss

/**
 * Builds a delta: an instruction stream plus a literal stream.
 *
 * Literals live in their own stream so they stay contiguous and compress well, and so adjacent
 * [add] calls collapse into a single ADD instruction.
 */
internal class DeltaWriter(estimatedTargetSize: Int = 1024) {

  private val instructions = ByteWriter(64)
  private val literals = ByteWriter((estimatedTargetSize / 8).coerceIn(64, 1 shl 20))

  private var pendingAdd = 0
  private var sourceCursor = 0
  private var targetSize = 0

  /** Number of target bytes described so far. */
  val length: Int get() = targetSize

  fun add(data: ByteArray, from: Int, to: Int) {
    if (to <= from) return
    literals.writeBytes(data, from, to)
    pendingAdd += to - from
    targetSize += to - from
  }

  fun copy(sourceOffset: Int, length: Int) {
    if (length <= 0) return
    flushAdd()
    writeTag(length, DeltaOp.COPY)
    instructions.writeSignedVarInt(sourceOffset - sourceCursor)
    sourceCursor = sourceOffset + length
    targetSize += length
  }

  /**
   * Describes `target[targetFrom, targetFrom + length)` as the byte-wise difference from
   * `source[sourceOffset, sourceOffset + length)`.
   *
   * This is what keeps a patch small when a region is *almost* a copy - recompiled code whose
   * embedded offsets shifted, say. The difference bytes are mostly zero, so they collapse in the
   * literal stream, whereas the same region emitted as literals would not compress at all.
   */
  fun diff(
    source: ByteArray,
    sourceOffset: Int,
    target: ByteArray,
    targetFrom: Int,
    length: Int
  ) {
    if (length <= 0) return
    flushAdd()
    writeTag(length, DeltaOp.DIFF)
    instructions.writeSignedVarInt(sourceOffset - sourceCursor)
    sourceCursor = sourceOffset + length
    for (i in 0 until length) {
      literals.writeByte(target[targetFrom + i] - source[sourceOffset + i])
    }
    targetSize += length
  }

  fun run(value: Byte, length: Int) {
    if (length <= 0) return
    flushAdd()
    writeTag(length, DeltaOp.RUN)
    instructions.writeByte(value.toInt() and 0xFF)
    targetSize += length
  }

  fun finish(): ByteArray {
    flushAdd()
    instructions.writeVarInt(DeltaOp.END)

    val instructionBytes = instructions.toByteArray()
    val literalBytes = literals.toByteArray()
    val packed = if (literalBytes.isEmpty()) literalBytes else Lzss.compress(literalBytes)
    val compressed = packed.size < literalBytes.size

    val out = ByteWriter(instructionBytes.size + minOf(packed.size, literalBytes.size) + 16)
    out.writeVarInt(instructionBytes.size)
    out.writeVarInt(literalBytes.size)
    out.writeByte(if (compressed) 1 else 0)
    val stored = if (compressed) packed else literalBytes
    out.writeVarInt(stored.size)
    out.writeBytes(instructionBytes)
    out.writeBytes(stored)
    return out.toByteArray()
  }

  private fun flushAdd() {
    if (pendingAdd == 0) return
    writeTag(pendingAdd, DeltaOp.ADD)
    pendingAdd = 0
  }

  private fun writeTag(length: Int, opcode: Int) {
    instructions.writeVarLong((length.toLong() shl DeltaOp.SHIFT) or opcode.toLong())
  }
}
