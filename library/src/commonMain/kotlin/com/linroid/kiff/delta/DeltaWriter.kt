package com.linroid.kiff.delta

import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.io.toIntIndex

/**
 * The bundled [DeltaSink]: builds the instruction stream for one region.
 *
 * Instructions belong to the region; literals do not. Content goes into the stream the whole patch
 * shares, passed in here, so that every region's content ends up contiguous and compresses as one
 * block - and so adjacent [add] calls still collapse into a single ADD instruction.
 *
 * Instruction lengths and source offsets are written as 64-bit varints, so the format addresses
 * inputs beyond 2 GB.
 */
internal class DeltaWriter(
  private val source: ByteArray,
  private val literals: ByteWriter
) : DeltaSink {

  private val instructions = ByteWriter(64)

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

  /** Size of the shared literal stream, counted before the patch packs it. */
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

  /** Closes the instruction stream and hands it over; the literals are already in the shared one. */
  fun finishInstructions(): ByteArray {
    flushAdd()
    instructions.writeVarInt(DeltaOp.END)
    return instructions.toByteArray()
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
