package com.linroid.kiff.text

import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter

/**
 * The TEXT region encoding: a line-level edit script over raw bytes.
 *
 * A line here is *bytes up to and including its newline*, and the final line may have none. That is
 * the whole trick. Splitting on `"\n"` and dropping empty trailing entries - which is what the
 * CLI's reader used to do - loses whether the file ended in a newline, so it can show a diff but
 * never restore one. Carrying the terminator inside the line means concatenating lines reproduces the
 * region byte for byte, which is what [com.linroid.kiff.Patcher] promises. CRLF needs no special
 * case: the `\r` simply belongs to the line it ends.
 *
 * Why bother, when the delta encoding can describe any bytes? Because a line-level script says
 * "these forty lines are unchanged" in three bytes, where a byte-level search has to rediscover
 * that by matching, and pays an instruction each time an edit interrupts the match. On source that
 * is edited in lines - manifests, configuration, anything generated as text - the difference is
 * most of the patch.
 */
internal object TextRegion {

  private const val NEWLINE = '\n'.code.toByte()

  private const val OP_END = 0
  private const val OP_EQUAL = 1
  private const val OP_DELETE = 2
  private const val OP_INSERT = 3

  /**
   * Offsets at which each line of `bytes[from, to)` starts, with [to] appended, so line `i` spans
   * `[starts[i], starts[i + 1])` and there are `size - 1` lines.
   */
  fun lineStarts(bytes: ByteArray, from: Int, to: Int): IntArray {
    if (to <= from) return intArrayOf(from)
    val starts = ArrayList<Int>(16)
    starts.add(from)
    for (i in from until to) {
      // A newline at the very end terminates the last line; it does not open an empty one.
      if (bytes[i] == NEWLINE && i + 1 < to) starts.add(i + 1)
    }
    starts.add(to)
    return starts.toIntArray()
  }

  /**
   * Largest number of lines either side may have.
   *
   * Myers keeps a snapshot per edit step, so its memory grows with the product of the input
   * lengths, not their sum. Past this many lines the line search is the wrong tool whatever the
   * bytes look like, and declining is better than exhausting memory to find out.
   */
  const val MAX_LINES = 20_000

  /**
   * Builds the edit script turning `source[sourceFrom, sourceTo)` into `target[targetFrom,
   * targetTo)`, appending inserted bytes to the patch's shared [literals] stream.
   *
   * Answers null when the region has too many lines to search; the caller falls back to bytes.
   */
  fun encode(
    algorithm: LineDiffAlgorithm,
    source: ByteArray,
    sourceFrom: Int,
    sourceTo: Int,
    target: ByteArray,
    targetFrom: Int,
    targetTo: Int,
    literals: ByteWriter
  ): ByteArray? {
    val sourceStarts = lineStarts(source, sourceFrom, sourceTo)
    val targetStarts = lineStarts(target, targetFrom, targetTo)
    if (sourceStarts.size - 1 > MAX_LINES || targetStarts.size - 1 > MAX_LINES) return null
    val sourceLines = keysOf(source, sourceStarts)
    val targetLines = keysOf(target, targetStarts)

    val out = ByteWriter(64)
    var sourceLine = 0
    var targetLine = 0

    for (edit in algorithm.diff(sourceLines, targetLines)) {
      when (edit) {
        is Edit.Equal -> {
          out.writeByte(OP_EQUAL)
          out.writeVarInt(edit.count)
          sourceLine += edit.count
          targetLine += edit.count
        }
        is Edit.Delete -> {
          out.writeByte(OP_DELETE)
          out.writeVarInt(edit.count)
          sourceLine += edit.count
        }
        is Edit.Insert -> {
          val bytes = edit.lines.size
          val from = targetStarts[targetLine]
          val to = targetStarts[targetLine + bytes]
          out.writeByte(OP_INSERT)
          out.writeVarInt(to - from)
          literals.writeBytes(target, from, to)
          targetLine += bytes
        }
      }
    }
    out.writeByte(OP_END)
    return out.toByteArray()
  }

  /**
   * Replays an edit script into `target[at, at + length)`, answering how many literal bytes it
   * consumed.
   */
  fun apply(
    source: ByteArray,
    sourceFrom: Int,
    sourceLength: Int,
    edits: ByteReader,
    literals: ByteArray,
    literalFrom: Int,
    target: ByteArray,
    at: Int,
    length: Int
  ): Int {
    if (sourceFrom < 0 || sourceFrom + sourceLength > source.size) {
      throw KiffException.InvalidPatch("Text region names a source range outside the source")
    }
    val starts = lineStarts(source, sourceFrom, sourceFrom + sourceLength)
    val lineCount = starts.size - 1

    val end = at + length
    var sourceLine = 0
    var targetPosition = at
    var literalPosition = literalFrom

    while (true) {
      when (val op = edits.readByte()) {
        OP_END -> break
        OP_EQUAL -> {
          val lines = edits.readVarInt()
          val from = lineAt(starts, sourceLine, lines, lineCount)
          val to = starts[sourceLine + lines]
          val span = to - from
          checkFits(targetPosition, span, end)
          source.copyInto(target, targetPosition, from, to)
          targetPosition += span
          sourceLine += lines
        }
        OP_DELETE -> {
          val lines = edits.readVarInt()
          lineAt(starts, sourceLine, lines, lineCount)
          sourceLine += lines
        }
        OP_INSERT -> {
          val span = edits.readVarInt()
          checkFits(targetPosition, span, end)
          if (span < 0 || literalPosition + span > literals.size) {
            throw KiffException.InvalidPatch("Text region reads past the literal stream")
          }
          literals.copyInto(target, targetPosition, literalPosition, literalPosition + span)
          targetPosition += span
          literalPosition += span
        }
        else -> throw KiffException.InvalidPatch("Unknown text edit opcode $op")
      }
    }
    if (targetPosition != end) {
      throw KiffException.InvalidPatch(
        "Text region produced ${targetPosition - at} of $length bytes"
      )
    }
    return literalPosition - literalFrom
  }

  /**
   * Lines as strings, one character per byte.
   *
   * The mapping is the identity on 0..255, so it round-trips any bytes at all - the strings are
   * only ever compared, never decoded as text, and lines that are not valid UTF-8 still diff
   * correctly.
   */
  private fun keysOf(bytes: ByteArray, starts: IntArray): List<String> =
    List(starts.size - 1) { line ->
      val from = starts[line]
      val to = starts[line + 1]
      val chars = CharArray(to - from)
      for (i in chars.indices) chars[i] = (bytes[from + i].toInt() and 0xFF).toChar()
      chars.concatToString()
    }

  private fun lineAt(starts: IntArray, line: Int, count: Int, lineCount: Int): Int {
    if (count < 0 || line + count > lineCount) {
      throw KiffException.InvalidPatch("Text region names lines outside the source region")
    }
    return starts[line]
  }

  private fun checkFits(position: Int, span: Int, end: Int) {
    if (span < 0 || position + span > end) {
      throw KiffException.InvalidPatch("Text region writes past the end of its region")
    }
  }
}
