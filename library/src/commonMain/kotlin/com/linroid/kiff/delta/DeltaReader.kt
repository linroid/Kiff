package com.linroid.kiff.delta

import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader

/**
 * Rebuilds one region's bytes from a source and the instruction stream [DeltaWriter] produced.
 *
 * One reader decodes every delta region: the instruction set is fixed, so nothing here has to know
 * which search produced the stream. What a region *is* - and which encoding it chose - is the
 * region tree's business, not this one's.
 */
internal object DeltaReader {

  /**
   * Applies the instructions in [instructions] to fill `target[at, at + length)`.
   *
   * Content comes from the patch's shared literal stream starting at [literalFrom]; the number of
   * literal bytes consumed is returned so the caller can advance to the next region.
   */
  fun apply(
    source: ByteArray,
    instructions: ByteReader,
    literals: ByteArray,
    literalFrom: Int,
    target: ByteArray,
    at: Int,
    length: Int
  ): Int {
    val end = at + length
    var targetPosition = at
    var literalPosition = literalFrom
    var sourceCursor = 0L

    while (true) {
      val tag = instructions.readVarLong()
      val opcode = (tag and DeltaOp.MASK).toInt()
      if (opcode == DeltaOp.END) break
      val run = (tag ushr DeltaOp.SHIFT).toIntLength()
      checkFits(targetPosition, run, end)
      when (opcode) {
        DeltaOp.ADD -> {
          checkLiterals(literalPosition, run, literals.size)
          literals.copyInto(target, targetPosition, literalPosition, literalPosition + run)
          literalPosition += run
        }
        DeltaOp.COPY -> {
          val sourceOffset = sourceCursor + instructions.readSignedVarLong()
          val from = checkInSource(sourceOffset, run, source.size)
          source.copyInto(target, targetPosition, from, from + run)
          sourceCursor = sourceOffset + run
        }
        DeltaOp.RUN -> {
          val value = instructions.readByte().toByte()
          target.fill(value, targetPosition, targetPosition + run)
        }
        DeltaOp.DIFF -> {
          val sourceOffset = sourceCursor + instructions.readSignedVarLong()
          val from = checkInSource(sourceOffset, run, source.size)
          checkLiterals(literalPosition, run, literals.size)
          for (i in 0 until run) {
            target[targetPosition + i] =
              (source[from + i] + literals[literalPosition + i]).toByte()
          }
          literalPosition += run
          sourceCursor = sourceOffset + run
        }
        else -> throw KiffException.InvalidPatch("Unknown delta opcode $opcode")
      }
      targetPosition += run
    }
    if (targetPosition != end) {
      throw KiffException.InvalidPatch(
        "Delta produced ${targetPosition - at} of $length bytes for a region"
      )
    }
    return literalPosition - literalFrom
  }

  private fun Long.toIntLength(): Int {
    if (this < 0 || this > Int.MAX_VALUE) {
      throw KiffException.InvalidPatch("Instruction length $this is out of range")
    }
    return toInt()
  }

  private fun checkLiterals(position: Int, length: Int, available: Int) {
    if (position + length > available) {
      throw KiffException.InvalidPatch("Delta reads past the literal stream")
    }
  }

  private fun checkInSource(sourceOffset: Long, length: Int, sourceSize: Int): Int {
    if (sourceOffset < 0 || sourceOffset + length > sourceSize) {
      throw KiffException.InvalidPatch(
        "Delta reads [$sourceOffset, ${sourceOffset + length}) outside the source"
      )
    }
    return sourceOffset.toInt()
  }

  private fun checkFits(position: Int, length: Int, end: Int) {
    if (length < 0 || position + length > end) {
      throw KiffException.InvalidPatch("Delta writes past the end of its region")
    }
  }
}
