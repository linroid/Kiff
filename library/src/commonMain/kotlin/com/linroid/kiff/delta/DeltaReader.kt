package com.linroid.kiff.delta

import com.linroid.kiff.KiffException
import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.internal.Lzss

/** Rebuilds target bytes from a source and a delta produced by [DeltaWriter]. */
internal object DeltaReader {

  fun apply(source: ByteArray, delta: ByteArray, deltaFrom: Int, targetSize: Int): ByteArray {
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

    val target = ByteArray(targetSize)
    var targetPosition = 0
    var literalPosition = 0
    var sourceCursor = 0

    while (true) {
      val tag = instructions.readVarLong()
      val opcode = (tag and 0x3L).toInt()
      if (opcode == DeltaOp.END) break
      val length = (tag ushr 2).toInt()
      checkFits(targetPosition, length, targetSize)
      when (opcode) {
        DeltaOp.ADD -> {
          if (literalPosition + length > literals.size) {
            throw KiffException.InvalidPatch("Delta reads past the literal stream")
          }
          literals.copyInto(target, targetPosition, literalPosition, literalPosition + length)
          literalPosition += length
        }
        DeltaOp.COPY -> {
          val sourceOffset = sourceCursor + instructions.readSignedVarInt()
          if (sourceOffset < 0 || sourceOffset + length > source.size) {
            throw KiffException.InvalidPatch(
              "Delta copies [$sourceOffset, ${sourceOffset + length}) outside the source"
            )
          }
          source.copyInto(target, targetPosition, sourceOffset, sourceOffset + length)
          sourceCursor = sourceOffset + length
        }
        DeltaOp.RUN -> {
          val value = instructions.readByte().toByte()
          target.fill(value, targetPosition, targetPosition + length)
        }
      }
      targetPosition += length
    }
    if (targetPosition != targetSize) {
      throw KiffException.InvalidPatch("Delta produced $targetPosition of $targetSize bytes")
    }
    return target
  }

  private fun checkFits(position: Int, length: Int, targetSize: Int) {
    if (length < 0 || position + length > targetSize) {
      throw KiffException.InvalidPatch("Delta writes past the end of the target")
    }
  }
}
