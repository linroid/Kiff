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
      append("--- ").append(sourceName).append('\n')
      append("+++ ").append(targetName).append('\n')
      for (hunk in hunks) {
        append("@@ -").append(range(hunk.sourceStart, hunk.sourceCount))
        append(" +").append(range(hunk.targetStart, hunk.targetCount)).append(" @@\n")
        for (line in hunk.lines) append(line).append('\n')
      }
    }
  }

  fun parse(text: String): Patch {
    val lines = text.split("\n")
    var at = 0
    var sourceName = "a"
    var targetName = "b"
    if (at < lines.size && lines[at].startsWith("--- ")) sourceName = lines[at++].substring(4)
    if (at < lines.size && lines[at].startsWith("+++ ")) targetName = lines[at++].substring(4)

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

    for (hunk in patch.hunks) {
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

      var previous = ' '
      for (line in hunk.lines) {
        if (line == NO_NEWLINE) {
          // The marker describes the line before it, so after a removed line it is a statement
          // about the source and says nothing about what the target ends with.
          if (previous != '-') targetEndsWithNewline = false
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
    val marks = Regex("""@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""").find(header)
      ?: throw KiffException.InvalidPatch("Malformed hunk header: $header")
    val (s, sc, t, tc) = marks.destructured
    val body = mutableListOf<String>()
    var at = bodyStart
    while (at < lines.size) {
      val line = lines[at]
      if (line.startsWith("@@")) break
      if (line.isEmpty() && at == lines.size - 1) { at++; break }
      body.add(line)
      at++
    }
    advance(at)
    return Hunk(
      sourceStart = s.toInt(),
      sourceCount = if (sc.isEmpty()) 1 else sc.toInt(),
      targetStart = t.toInt(),
      targetCount = if (tc.isEmpty()) 1 else tc.toInt(),
      lines = body
    )
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
    val tagged = mutableListOf<Tagged>()
    var sourceIndex = 0
    var targetIndex = 0
    for (edit in edits) {
      when (edit) {
        is Edit.Equal -> repeat(edit.count) {
          tagged.add(Tagged(' ', sourceIndex++, targetIndex++, null))
        }
        is Edit.Delete -> repeat(edit.count) {
          tagged.add(Tagged('-', sourceIndex++, targetIndex, null))
        }
        is Edit.Insert -> for (line in edit.lines) {
          tagged.add(Tagged('+', sourceIndex, targetIndex++, line))
        }
      }
    }

    // A file that gained or lost its final newline has no changed *lines* at all, so without this
    // two different files would diff to nothing. The last line is what changed: it is replaced by
    // itself, and the marker says how.
    val newlineChanged = source.endsWithNewline != target.endsWithNewline
    if (newlineChanged && tagged.isNotEmpty() && tagged.last().marker == ' ') {
      val last = tagged.removeAt(tagged.lastIndex)
      tagged.add(Tagged('-', last.sourceIndex, last.targetIndex, null))
      tagged.add(
        Tagged('+', last.sourceIndex + 1, last.targetIndex, source.lines[last.sourceIndex])
      )
    }

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
        when (entry.marker) {
          ' ' -> {
            body.add(" " + source.lines[entry.sourceIndex])
            sourceLines++
            targetLines++
          }
          '-' -> {
            body.add("-" + source.lines[entry.sourceIndex])
            sourceLines++
            if (!source.endsWithNewline && entry.sourceIndex == source.lines.lastIndex) {
              body.add(NO_NEWLINE)
            }
          }
          else -> {
            body.add("+" + entry.text)
            targetLines++
          }
        }
      }
      if (to == tagged.size - 1 && !target.endsWithNewline && body.last().startsWith("+")) {
        body.add(NO_NEWLINE)
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
