package com.linroid.kiff.text

import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.io.RestoreTarget

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
    val starts = IntArray(lineCount(bytes, from, to, Int.MAX_VALUE) + 1)
    var line = 0
    starts[line++] = from
    // A newline at the very end terminates the last line; it does not open an empty one.
    for (i in from until to - 1) {
      if (bytes[i] == NEWLINE) starts[line++] = i + 1
    }
    if (to > from) starts[line] = to
    return starts
  }

  /** Lines in `bytes[from, to)`, counted no further than one past [limit]. */
  private fun lineCount(bytes: ByteArray, from: Int, to: Int, limit: Int): Int {
    if (to <= from) return 0
    var lines = 1
    for (i in from until to - 1) {
      if (bytes[i] == NEWLINE && ++lines > limit) return lines
    }
    return lines
  }

  /**
   * Largest number of lines either side may have.
   *
   * The line search takes time in proportion to the lines times the edits it finds. Past this many
   * lines it is the wrong tool whatever the bytes look like, and declining is cheaper than finding
   * out.
   */
  const val MAX_LINES = 20_000

  /**
   * Most lines a script may delete and insert before the region is left to the byte search.
   *
   * A changed line costs its whole length in a line script, where the byte search pays only for
   * the bytes that differ, so a script this long is rarely the smallest candidate; and the search
   * for it costs time in proportion to its length. Declining here costs nothing: the byte-level
   * encoding is built for every region whatever the plan.
   */
  const val MAX_EDITS = 4_096

  /**
   * Builds the edit script turning `source[sourceFrom, sourceTo)` into `target[targetFrom,
   * targetTo)`, appending inserted bytes to the patch's shared [literals] stream.
   *
   * Answers null when the region has too many lines to search, or needs too many edits; the caller
   * falls back to bytes.
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
    // Counted before anything is allocated, so a region with too many lines costs one pass to turn
    // down rather than an index of all of them.
    if (lineCount(source, sourceFrom, sourceTo, MAX_LINES) > MAX_LINES ||
      lineCount(target, targetFrom, targetTo, MAX_LINES) > MAX_LINES
    ) {
      return null
    }
    val sourceStarts = lineStarts(source, sourceFrom, sourceTo)
    val targetStarts = lineStarts(target, targetFrom, targetTo)
    val sourceLines = keysOf(source, sourceStarts)
    val targetLines = keysOf(target, targetStarts)

    val edits = algorithm.diffWithin(sourceLines, targetLines, MAX_EDITS) ?: return null

    val out = ByteWriter(64)
    var sourceLine = 0
    var targetLine = 0

    for (edit in edits) {
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
    edits: ByteReader,
    literals: ByteArray,
    literalFrom: Int,
    out: RestoreTarget,
    length: Int
  ): Int {
    // Lines are found as the script reaches them, walking forward from the last. An index of every
    // line would cost several times the range in memory, and the range is the patch's to choose.
    var cursor = 0
    var produced = 0
    var literalPosition = literalFrom

    while (true) {
      when (val op = edits.readByte()) {
        OP_END -> break
        OP_EQUAL -> {
          val from = cursor
          cursor = skipLines(source, cursor, edits.readVarInt())
          val span = cursor - from
          checkFits(produced, span, length)
          out.write(source, from, cursor)
          produced += span
        }
        OP_DELETE -> cursor = skipLines(source, cursor, edits.readVarInt())
        OP_INSERT -> {
          val span = edits.readVarInt()
          checkFits(produced, span, length)
          if (span > literals.size - literalPosition) {
            throw KiffException.InvalidPatch("Text region reads past the literal stream")
          }
          out.write(literals, literalPosition, literalPosition + span)
          produced += span
          literalPosition += span
        }
        else -> throw KiffException.InvalidPatch("Unknown text edit opcode $op")
      }
    }
    if (produced != length) {
      throw KiffException.InvalidPatch("Text region produced $produced of $length bytes")
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

  /**
   * Where the line [count] lines after the one starting at [from] starts, or the end of [source]
   * after its last line.
   *
   * A count past the last line is refused as soon as the walk reaches the end, so even one near
   * Int.MAX_VALUE costs no more than a pass over the source.
   */
  private fun skipLines(source: ByteArray, from: Int, count: Int): Int {
    if (count < 0) {
      throw KiffException.InvalidPatch("Text region names lines outside the source region")
    }
    var at = from
    repeat(count) {
      if (at >= source.size) {
        throw KiffException.InvalidPatch("Text region names lines outside the source region")
      }
      // Up to and including the newline, or to the end when the last line has none.
      while (at < source.size) {
        if (source[at++] == NEWLINE) break
      }
    }
    return at
  }

  private fun checkFits(produced: Int, span: Int, length: Int) {
    if (span < 0 || span > length - produced) {
      throw KiffException.InvalidPatch("Text region writes past the end of its region")
    }
  }
}
