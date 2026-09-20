package com.linroid.kiff.delta

import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.readFully

/**
 * Rebuilds one region's bytes from a source and the instruction stream [DeltaWriter] produced.
 *
 * One reader decodes every delta region: the instruction set is fixed, so nothing here has to know
 * which search produced the stream. What a region *is* - and which encoding it chose - is the
 * region tree's business, not this one's.
 *
 * Nothing is held whole. The source is addressed rather than read in, the target is written out as
 * it is produced, and the only buffer is one of [CHUNK] bytes reused between instructions.
 */
internal class DeltaReader(private val buffer: ByteArray = ByteArray(CHUNK)) {

  /**
   * Applies the instructions in [instructions], writing [length] bytes to [out].
   *
   * Content comes from the patch's shared literal stream starting at [literalFrom]; the number of
   * literal bytes consumed is returned so the caller can advance to the next region.
   */
  fun apply(
    source: SeekableSource,
    instructions: ByteReader,
    literals: ByteArray,
    literalFrom: Int,
    out: RestoreTarget,
    length: Int
  ): Int {
    var produced = 0
    var literalPosition = literalFrom
    var sourceCursor = 0L

    while (true) {
      val tag = instructions.readVarLong()
      val opcode = (tag and DeltaOp.MASK).toInt()
      if (opcode == DeltaOp.END) break
      val run = (tag ushr DeltaOp.SHIFT).toRunLength()
      if (produced + run > length) {
        throw KiffException.InvalidPatch("Delta writes past the end of its region")
      }
      when (opcode) {
        DeltaOp.ADD -> {
          checkLiterals(literalPosition, run, literals.size)
          out.write(literals, literalPosition, literalPosition + run)
          literalPosition += run
        }
        DeltaOp.COPY -> {
          val offset = sourceCursor + instructions.readSignedVarLong()
          checkInSource(offset, run, source.size)
          copy(source, offset, run, out)
          sourceCursor = offset + run
        }
        DeltaOp.RUN -> {
          val value = instructions.readByte().toByte()
          fill(value, run, out)
        }
        DeltaOp.DIFF -> {
          val offset = sourceCursor + instructions.readSignedVarLong()
          checkInSource(offset, run, source.size)
          checkLiterals(literalPosition, run, literals.size)
          difference(source, offset, run, literals, literalPosition, out)
          literalPosition += run
          sourceCursor = offset + run
        }
        else -> throw KiffException.InvalidPatch("Unknown delta opcode $opcode")
      }
      produced += run
    }
    if (produced != length) {
      throw KiffException.InvalidPatch("Delta produced $produced of $length bytes for a region")
    }
    return literalPosition - literalFrom
  }

  private fun copy(source: SeekableSource, at: Long, length: Int, out: RestoreTarget) {
    var done = 0
    while (done < length) {
      val count = minOf(buffer.size, length - done)
      source.readFully(at + done, buffer, 0, count)
      out.write(buffer, 0, count)
      done += count
    }
  }

  /** The source bytes plus the differences the patch carries for them. */
  private fun difference(
    source: SeekableSource,
    at: Long,
    length: Int,
    literals: ByteArray,
    literalFrom: Int,
    out: RestoreTarget
  ) {
    var done = 0
    while (done < length) {
      val count = minOf(buffer.size, length - done)
      source.readFully(at + done, buffer, 0, count)
      for (i in 0 until count) {
        buffer[i] = (buffer[i] + literals[literalFrom + done + i]).toByte()
      }
      out.write(buffer, 0, count)
      done += count
    }
  }

  private fun fill(value: Byte, length: Int, out: RestoreTarget) {
    val filled = minOf(buffer.size, length)
    buffer.fill(value, 0, filled)
    var done = 0
    while (done < length) {
      val step = minOf(filled, length - done)
      out.write(buffer, 0, step)
      done += step
    }
  }

  private fun Long.toRunLength(): Int {
    if (this < 0 || this > Int.MAX_VALUE) {
      throw KiffException.InvalidPatch("Instruction length $this is out of range")
    }
    return toInt()
  }

  private fun checkLiterals(position: Int, length: Int, available: Int) {
    if (position < 0 || position + length > available) {
      throw KiffException.InvalidPatch("Delta reads past the literal stream")
    }
  }

  private fun checkInSource(offset: Long, length: Int, size: Long) {
    if (offset < 0 || offset + length > size) {
      throw KiffException.InvalidPatch(
        "Delta reads [$offset, ${offset + length}) outside the source"
      )
    }
  }

  private companion object {
    /** Big enough that a large copy is a handful of reads, small enough to be nothing. */
    const val CHUNK = 1 shl 16
  }
}
