package com.linroid.kiff.delta

import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.Lzss
import com.linroid.kiff.io.toIntIndex

/**
 * The bundled [DeltaSink]: builds an instruction stream plus a literal stream.
 *
 * Literals live in their own stream so they stay contiguous and compress well, and so adjacent
 * [add] calls collapse into a single ADD instruction.
 *
 * Instruction lengths and source offsets are written as 64-bit varints, so the format addresses
 * inputs beyond 2 GB.
 */
internal class DeltaWriter(
  private val source: ByteArray,
  estimatedTargetSize: Long = 1024
) : DeltaSink {

  private val instructions = ByteWriter(64)
  private val literals = ByteWriter(
    (estimatedTargetSize / 8).coerceIn(64, 1L shl 20).toInt()
  )

  private var pendingAdd = 0L
  private var sourceCursor = 0L
  private var targetSize = 0L

  /** Number of target bytes described so far. */
  val length: Long get() = targetSize

  /**
   * Bytes of instruction stream written so far.
   *
   * A pending ADD's tag is not written until the next instruction flushes it, so a few bytes land
   * on whichever region triggers that flush.
   */
  val instructionBytes: Long get() = instructions.size.toLong()

  /** Bytes of literal stream written so far, counted before [finish] packs them. */
  val literalBytes: Long get() = literals.size.toLong()

  override fun add(bytes: ByteArray, from: Int, to: Int) {
    if (to <= from) return
    literals.writeBytes(bytes, from, to)
    pendingAdd += to - from
    targetSize += to - from
  }

  override fun copy(sourceOffset: Long, length: Long) {
    if (length <= 0) return
    flushAdd()
    writeTag(length, DeltaOp.COPY)
    instructions.writeSignedVarLong(sourceOffset - sourceCursor)
    sourceCursor = sourceOffset + length
    targetSize += length
  }

  override fun diff(sourceOffset: Long, target: ByteArray, from: Int, to: Int) {
    val length = (to - from).toLong()
    if (length <= 0) return
    flushAdd()
    writeTag(length, DeltaOp.DIFF)
    instructions.writeSignedVarLong(sourceOffset - sourceCursor)
    val base = sourceOffset.toIntIndex("Source offset")
    for (i in 0 until (to - from)) {
      literals.writeByte(target[from + i] - source[base + i])
    }
    sourceCursor = sourceOffset + length
    targetSize += length
  }

  override fun run(value: Byte, length: Long) {
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
    if (pendingAdd == 0L) return
    writeTag(pendingAdd, DeltaOp.ADD)
    pendingAdd = 0
  }

  private fun writeTag(length: Long, opcode: Int) {
    instructions.writeVarLong((length shl DeltaOp.SHIFT) or opcode.toLong())
  }
}
