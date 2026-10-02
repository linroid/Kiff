package com.linroid.kiff.text

import com.linroid.kiff.KiffException

/**
 * The one genuinely standard format in reach.
 *
 * Every tool that reads a diff reads this one, so emitting it costs almost nothing and buys
 * `patch(1)`, code review, and anything else that has ever parsed a diff. It is a text format for
 * text files and has nothing to do with the patch container, which describes any bytes at all.
 */
object UnifiedDiff {

  private const val DEFAULT_CONTEXT = 3
  private const val NO_NEWLINE = "\\ No newline at end of file"
  private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

  /** One run of changes, with the unchanged lines around it that let it be located. */
  data class Hunk(
    val sourceStart: Int,
    val sourceCount: Int,
    val targetStart: Int,
    val targetCount: Int,
    /** Each line prefixed with ' ', '-' or '+'. */
    val lines: List<String>
  )

  data class Patch(
    val sourceName: String,
    val targetName: String,
    val hunks: List<Hunk>
  )

  fun format(
    source: TextContent,
    target: TextContent,
    edits: List<Edit>,
    sourceName: String = "a",
    targetName: String = "b",
    context: Int = DEFAULT_CONTEXT
  ): String {
    require(context >= 0) { "Context must not be negative: $context" }
    val hunks = hunksOf(source, target, edits, context)
    if (hunks.isEmpty()) return ""
    return buildString {
      append("--- ").append(headerName(sourceName)).append('\n')
      append("+++ ").append(headerName(targetName)).append('\n')
      for (hunk in hunks) {
        append("@@ -").append(range(hunk.sourceStart, hunk.sourceCount))
        append(" +").append(range(hunk.targetStart, hunk.targetCount)).append(" @@\n")
        for (line in hunk.lines) append(line).append('\n')
      }
    }
  }

  /**
   * Reads a unified diff of one file.
   *
   * Each hunk is read to exactly the line counts its header gives, as patch(1) and git apply read
   * it: a hunk that ends early was cut short and is refused rather than applied in part, and lines
   * past its counts are not part of it. Anything before the `---`/`+++` pair, such as git's
   * `diff --git` and `index` lines, is skipped.
   */
  fun parse(text: String): Patch {
    val lines = text.split("\n")
    var at = 0
    var sourceName = "a"
    var targetName = "b"
    val names = lines.indices.firstOrNull { i ->
      lines[i].startsWith("@@") ||
        (lines[i].startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ "))
    }
    if (names != null && !lines[names].startsWith("@@")) {
      sourceName = nameOf(lines[names].substring(4))
      targetName = nameOf(lines[names + 1].substring(4))
      at = names + 2
    }

    val hunks = mutableListOf<Hunk>()
    while (at < lines.size) {
      val header = lines[at]
      if (header.isEmpty()) { at++; continue }
      if (!header.startsWith("@@")) {
        throw KiffException.InvalidPatch("Expected a hunk header, found: $header")
      }
      hunks.add(parseHunk(header, lines, at + 1) { at = it })
    }
    return Patch(sourceName, targetName, hunks)
  }

  /** Rebuilds the target, refusing a hunk whose context does not match what it claims. */
  fun apply(source: TextContent, patch: Patch): TextContent {
    if (patch.hunks.isEmpty()) return source
    val out = mutableListOf<String>()
    var cursor = 0
    // Null until something settles it. The marker only ever says a file lacks a final newline, so
    // its absence where the diff reaches the end means the target has one.
    var targetEndsWithNewline: Boolean? = null
    // The output line a marker said ends the target, which nothing may follow; -1 until one does.
    var unterminated = -1

    for (hunk in patch.hunks) {
      // A hunk built in code rather than parsed has not had its counts checked against its body.
      val sourceLines = hunk.lines.count { it.startsWith(" ") || it.startsWith("-") }
      val targetLines = hunk.lines.count { it.startsWith(" ") || it.startsWith("+") }
      if (sourceLines != hunk.sourceCount || targetLines != hunk.targetCount) {
        throw KiffException.InvalidPatch(
          "Hunk at ${hunk.sourceStart} holds $sourceLines source and $targetLines target line(s) " +
            "but declares ${hunk.sourceCount} and ${hunk.targetCount}"
        )
      }
      // A range of no lines is numbered by the line it follows, so `-4,0` inserts after line 4 and
      // `-0,0` at the very top; any other range is numbered by its first line.
      if (hunk.sourceStart == 0 && hunk.sourceCount > 0) {
        throw KiffException.InvalidPatch("Hunk at 0 claims ${hunk.sourceCount} source line(s)")
      }
      val start = if (hunk.sourceCount == 0) hunk.sourceStart else hunk.sourceStart - 1
      if (start < cursor || start > source.lines.size) {
        throw KiffException.InvalidPatch(
          "Hunk at ${hunk.sourceStart} is out of order or past the end"
        )
      }
      for (i in cursor until start) out.add(source.lines[i])
      cursor = start

      var previous: Char? = null
      for (line in hunk.lines) {
        if (isMarker(line)) {
          // The marker says the line before it ends its file without a newline: the source's after
          // a removed line, the target's after an added one, and both after an unchanged one. A
          // claim about the source is checked like any other context; one about the target is
          // held to until the end, since only the very last line can lack a newline.
          if (previous == null || previous == '\\') {
            throw KiffException.InvalidPatch("No-newline marker follows no line")
          }
          if (previous != '+' && (cursor != source.lines.size || source.endsWithNewline)) {
            throw KiffException.InvalidPatch(
              "Hunk says source line $cursor ends the file without a newline, and it does not"
            )
          }
          if (previous != '-') {
            targetEndsWithNewline = false
            unterminated = out.lastIndex
          }
          previous = '\\'
          continue
        }
        when {
          line.startsWith("+") -> out.add(line.substring(1))
          line.startsWith("-") || line.startsWith(" ") -> {
            val expected = line.substring(1)
            if (cursor >= source.lines.size || source.lines[cursor] != expected) {
              throw KiffException.InvalidPatch(
                "Hunk does not match the source at line ${cursor + 1}: expected '$expected'"
              )
            }
            if (line.startsWith(" ")) out.add(expected)
            cursor++
          }
          else -> throw KiffException.InvalidPatch("Unknown diff line: $line")
        }
        previous = line[0]
      }
    }
    for (i in cursor until source.lines.size) out.add(source.lines[i])
    if (unterminated >= 0 && unterminated != out.lastIndex) {
      throw KiffException.InvalidPatch("No-newline marker is not at the end of the file")
    }

    if (targetEndsWithNewline == null && cursor >= source.lines.size) {
      // The diff described the file all the way to its end and never said otherwise.
      targetEndsWithNewline = true
    }
    return TextContent(out, targetEndsWithNewline ?: source.endsWithNewline)
  }

  private inline fun parseHunk(
    header: String,
    lines: List<String>,
    bodyStart: Int,
    advance: (Int) -> Unit
  ): Hunk {
    val marks = HUNK_HEADER.find(header)
      ?: throw KiffException.InvalidPatch("Malformed hunk header: $header")
    val (s, sc, t, tc) = marks.destructured
    val sourceCount = if (sc.isEmpty()) 1 else number(sc, header)
    val targetCount = if (tc.isEmpty()) 1 else number(tc, header)

    // Splitting text that ends in a newline leaves one empty string after it, which is not a line.
    val end = if (lines.lastOrNull() == "") lines.lastIndex else lines.size
    var sourceLeft = sourceCount
    var targetLeft = targetCount
    val body = mutableListOf<String>()
    var at = bodyStart
    while (sourceLeft > 0 || targetLeft > 0) {
      if (at >= end || lines[at].startsWith("@@")) {
        throw KiffException.InvalidPatch("Hunk ends before its counts: $header")
      }
      val line = lines[at++]
      when {
        isMarker(line) -> body.add(NO_NEWLINE)
        // An empty line is a context line whose leading space an editor or a mailer removed, and
        // it is read as one by patch(1) and git apply alike.
        line.isEmpty() || line[0] == ' ' -> {
          body.add(" " + line.drop(1))
          sourceLeft--
          targetLeft--
        }
        line[0] == '-' -> {
          body.add(line)
          sourceLeft--
        }
        line[0] == '+' -> {
          body.add(line)
          targetLeft--
        }
        else -> throw KiffException.InvalidPatch("Unknown diff line: $line")
      }
      if (sourceLeft < 0 || targetLeft < 0) {
        throw KiffException.InvalidPatch("Hunk overruns its counts: $header")
      }
    }
    // The hunk's last line may still carry its marker.
    if (at < end && isMarker(lines[at])) {
      body.add(NO_NEWLINE)
      at++
    }
    advance(at)
    return Hunk(
      sourceStart = number(s, header),
      sourceCount = sourceCount,
      targetStart = number(t, header),
      targetCount = targetCount,
      lines = body
    )
  }

  private fun number(digits: String, header: String): Int = digits.toIntOrNull()
    ?: throw KiffException.InvalidPatch("Hunk header number is out of range: $header")

  /** The marker is localised by some tools, so any line opening with a backslash is one. */
  private fun isMarker(line: String) = line.startsWith("\\")

  /**
   * A name as a header line carries it.
   *
   * GNU diff and git end a name containing a space with a tab, which is how patch(1) tells it from
   * a timestamp, and git quotes a name holding a quote, a backslash or a control character. Any
   * other name is written as it is.
   */
  private fun headerName(name: String): String {
    if (name.none { it == '"' || it == '\\' || it < ' ' || it == '\u007f' }) {
      return if (' ' in name) "$name\t" else name
    }
    return buildString {
      append('"')
      for (c in name) {
        when (c) {
          '"' -> append("\\\"")
          '\\' -> append("\\\\")
          '\t' -> append("\\t")
          '\n' -> append("\\n")
          '\r' -> append("\\r")
          else -> if (c < ' ' || c == '\u007f') {
            append('\\').append(c.code.toString(8).padStart(3, '0'))
          } else {
            append(c)
          }
        }
      }
      append('"')
    }
  }

  /** Reads a name back from a header line: unquoted as git quotes it, or up to the first tab. */
  private fun nameOf(field: String): String {
    if (!field.startsWith('"')) return field.substringBefore('\t')
    // An octal escape is one byte of the name's UTF-8, so the name is rebuilt as bytes.
    val bytes = mutableListOf<Byte>()
    var i = 1
    while (true) {
      val plain = field.indexOfAny(charArrayOf('"', '\\'), i)
      if (plain < 0) throw KiffException.InvalidPatch("Unterminated quoted name: $field")
      bytes.addAll(field.substring(i, plain).encodeToByteArray().asList())
      i = plain + 1
      if (field[plain] == '"') break
      if (i >= field.length) throw KiffException.InvalidPatch("Unterminated quoted name: $field")
      val escaped = field[i++]
      val byte = when (escaped) {
        'a' -> 7
        'b' -> 8
        't' -> 9
        'n' -> 10
        'v' -> 11
        'f' -> 12
        'r' -> 13
        '"', '\\' -> escaped.code
        in '0'..'7' -> {
          var value = escaped - '0'
          repeat(2) {
            if (i < field.length && field[i] in '0'..'7') value = value * 8 + (field[i++] - '0')
          }
          value
        }
        else -> throw KiffException.InvalidPatch("Unknown escape in quoted name: $field")
      }
      bytes.add(byte.toByte())
    }
    return bytes.toByteArray().decodeToString()
  }

  private fun range(start: Int, count: Int) = if (count == 1) "$start" else "$start,$count"

  private fun hunksOf(
    source: TextContent,
    target: TextContent,
    edits: List<Edit>,
    context: Int
  ): List<Hunk> {
    // Flatten the edit script into one tagged line per source or target line, which is the shape
    // hunks are cut from. Each entry also records how many source and target lines precede it,
    // which is all a hunk header needs. An inserted line carries its own text, so nothing has to
    // stay in step with a separate list of them.
    val flat = mutableListOf<Tagged>()
    var sourceIndex = 0
    var targetIndex = 0
    for (edit in edits) {
      when (edit) {
        is Edit.Equal -> repeat(edit.count) {
          flat.add(Tagged(' ', sourceIndex++, targetIndex++, null))
        }
        is Edit.Delete -> repeat(edit.count) {
          flat.add(Tagged('-', sourceIndex++, targetIndex, null))
        }
        is Edit.Insert -> for (line in edit.lines) {
          flat.add(Tagged('+', sourceIndex, targetIndex++, line))
        }
      }
    }

    val tagged = splitWhereTerminatorsDiffer(flat, source, target)
    val changed = tagged.indices.filter { tagged[it].marker != ' ' }
    if (changed.isEmpty()) return emptyList()

    // No hunk can use more context than there are lines, and the clamp keeps the arithmetic below
    // clear of overflow however large a context is asked for.
    val reach = minOf(context, tagged.size)
    val hunks = mutableListOf<Hunk>()
    var group = 0
    while (group < changed.size) {
      // Changes share a hunk when the unchanged lines between them are few enough for the context
      // of both to cover, which is where GNU diff and git draw the line too.
      var last = group
      while (last + 1 < changed.size && changed[last + 1] - changed[last] - 1 <= 2L * reach) last++
      val from = maxOf(0, changed[group] - reach)
      val to = minOf(tagged.size - 1, changed[last] + reach)

      val body = mutableListOf<String>()
      var sourceLines = 0
      var targetLines = 0
      for (i in from..to) {
        val entry = tagged[i]
        // The marker follows whichever printed line is the unterminated end of its file. After the
        // split, an unchanged line that is one is the unterminated end of both.
        when (entry.marker) {
          ' ' -> {
            body.add(" " + source.lines[entry.sourceIndex])
            sourceLines++
            targetLines++
            if (!terminated(source, entry.sourceIndex)) body.add(NO_NEWLINE)
          }
          '-' -> {
            body.add("-" + source.lines[entry.sourceIndex])
            sourceLines++
            if (!terminated(source, entry.sourceIndex)) body.add(NO_NEWLINE)
          }
          else -> {
            body.add("+" + entry.text)
            targetLines++
            if (!terminated(target, entry.targetIndex)) body.add(NO_NEWLINE)
          }
        }
      }

      // A range is numbered by its first line, and a range of no lines by the line it follows, so
      // an insertion lands after whatever precedes it and at the top only when nothing does. Each
      // side is counted on its own, so a hunk's target position includes every earlier hunk's
      // change in length.
      val sourceBefore = tagged[from].sourceIndex
      val targetBefore = tagged[from].targetIndex
      hunks.add(
        Hunk(
          sourceStart = if (sourceLines == 0) sourceBefore else sourceBefore + 1,
          sourceCount = sourceLines,
          targetStart = if (targetLines == 0) targetBefore else targetBefore + 1,
          targetCount = targetLines,
          lines = body
        )
      )
      group = last + 1
    }
    return hunks
  }

  /**
   * Turns every unchanged line whose two copies disagree on ending in a newline into a removal and
   * an insertion.
   *
   * Unified diff names lines without their terminators, so the last line of a file that lacks a
   * final newline looks equal to the same text with one - and a diff that left it as context
   * would describe two different files as one. Such a line did change. It is removed, together
   * with any lines removed right after it, and put back after them, which is the order GNU diff
   * prints; the markers then say which copy lacks the newline. Only a pair that touches the last
   * line of a file can disagree.
   */
  private fun splitWhereTerminatorsDiffer(
    flat: List<Tagged>,
    source: TextContent,
    target: TextContent
  ): List<Tagged> {
    val split = ArrayList<Tagged>(flat.size + 1)
    var i = 0
    while (i < flat.size) {
      val entry = flat[i]
      if (entry.marker != ' ' ||
        terminated(source, entry.sourceIndex) == terminated(target, entry.targetIndex)
      ) {
        split.add(entry)
        i++
        continue
      }
      // The removals that follow now come before the line goes back in, so they precede it on the
      // target side.
      split.add(Tagged('-', entry.sourceIndex, entry.targetIndex, null))
      var next = i + 1
      while (next < flat.size && flat[next].marker == '-') {
        split.add(Tagged('-', flat[next].sourceIndex, entry.targetIndex, null))
        next++
      }
      val sourceAfter = entry.sourceIndex + (next - i)
      split.add(Tagged('+', sourceAfter, entry.targetIndex, target.lines[entry.targetIndex]))
      i = next
    }
    return split
  }

  /** Whether line [index] of [text] ends in a newline: every line but an unterminated last. */
  private fun terminated(text: TextContent, index: Int) =
    index < text.lines.lastIndex || text.endsWithNewline

  /**
   * One line of the flattened edit script. [sourceIndex] and [targetIndex] count the lines of each
   * side that come before it, which for a line that is on that side is also its own index.
   */
  private class Tagged(
    val marker: Char,
    val sourceIndex: Int,
    val targetIndex: Int,
    val text: String?
  )
}
