package com.linroid.kiff.text

interface LineDiffAlgorithm {
  val name: String

  fun diff(source: List<String>, target: List<String>): List<Edit>

  /**
   * [diff], or null when turning [source] into [target] takes more than [maxEdits] deleted and
   * inserted lines.
   *
   * The patch encoder asks this rather than [diff]. A line script is one candidate among several
   * for a region, and a long one is rarely the smallest - a changed line costs its whole length
   * either way - while finding it costs time in proportion to its length. An algorithm that can
   * give up once it knows the answer is too long should; this default finds the whole script and
   * checks it afterwards, which bounds nothing but is never wrong.
   */
  fun diffWithin(source: List<String>, target: List<String>, maxEdits: Int): List<Edit>? =
    diff(source, target).takeIf { edits ->
      edits.sumOf { edit ->
        when (edit) {
          is Edit.Delete -> edit.count.toLong()
          is Edit.Insert -> edit.lines.size.toLong()
          is Edit.Equal -> 0L
        }
      } <= maxEdits
    }
}
