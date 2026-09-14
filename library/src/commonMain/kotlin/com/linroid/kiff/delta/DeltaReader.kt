package com.linroid.kiff.delta

import com.linroid.kiff.KiffException
import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.internal.Lzss
import com.linroid.kiff.internal.toIntIndex

/**
 * Rebuilds target bytes from a source and a delta produced by [DeltaWriter].
 *
 * One reader decodes every algorithm's output: the instruction set is fixed, so nothing here has to
 * know which search produced the stream.
 */
internal object DeltaReader {

  fun apply(source: ByteArray, delta: ByteArray, deltaFrom: Int, targetSize: Long): ByteArray {
    val size = targetSize.toIntIndex("Target size")
    val container = ByteReader(delta, deltaFrom)
    val instructionLength = container.readVarInt()
    val literalLength = container.readVarInt()
    val literalFlag = container.readByte()
    val storedLength = container.readVarInt()

    val instructions = ByteReader(delta, container.offset)
    container.skip(instructionLength)
    val storedLiterals = container.readBytes(storedLength)
    val literals = when (literalFlag) {
      0 -> storedLiterals
      1 -> Lzss.decompress(storedLiterals, literalLength)
      else -> throw KiffException.InvalidPatch("Unknown literal encoding $literalFlag")
    }

    val target = ByteArray(size)
    var targetPosition = 0
    var literalPosition = 0
    var sourceCursor = 0L

    while (true) {
      val tag = instructions.readVarLong()
      val opcode = (tag and DeltaOp.MASK).toInt()
      if (opcode == DeltaOp.END) break
      val length = (tag ushr DeltaOp.SHIFT).toIntIndex("Instruction length")
      checkFits(targetPosition, length, size)
      when (opcode) {
        DeltaOp.ADD -> {
          if (literalPosition + length > literals.size) {
            throw KiffException.InvalidPatch("Delta reads past the literal stream")
          }
          literals.copyInto(target, targetPosition, literalPosition, literalPosition + length)
          literalPosition += length
        }
        DeltaOp.COPY -> {
          val sourceOffset = sourceCursor + instructions.readSignedVarLong()
          val at = checkInSource(sourceOffset, length, source.size)
          source.copyInto(target, targetPosition, at, at + length)
          sourceCursor = sourceOffset + length
        }
        DeltaOp.RUN -> {
          val value = instructions.readByte().toByte()
          target.fill(value, targetPosition, targetPosition + length)
        }
        DeltaOp.DIFF -> {
          val sourceOffset = sourceCursor + instructions.readSignedVarLong()
          val at = checkInSource(sourceOffset, length, source.size)
          if (literalPosition + length > literals.size) {
            throw KiffException.InvalidPatch("Delta reads past the literal stream")
          }
          for (i in 0 until length) {
            target[targetPosition + i] =
              (source[at + i] + literals[literalPosition + i]).toByte()
          }
          literalPosition += length
          sourceCursor = sourceOffset + length
        }
        else -> throw KiffException.InvalidPatch("Unknown delta opcode $opcode")
      }
      targetPosition += length
    }
    if (targetPosition != size) {
      throw KiffException.InvalidPatch("Delta produced $targetPosition of $size bytes")
    }
    return target
  }

  private fun checkInSource(sourceOffset: Long, length: Int, sourceSize: Int): Int {
    if (sourceOffset < 0 || sourceOffset + length > sourceSize) {
      throw KiffException.InvalidPatch(
        "Delta reads [$sourceOffset, ${sourceOffset + length}) outside the source"
      )
    }
    return sourceOffset.toInt()
  }

  private fun checkFits(position: Int, length: Int, targetSize: Int) {
    if (length < 0 || position + length > targetSize) {
      throw KiffException.InvalidPatch("Delta writes past the end of the target")
    }
  }
}
