package com.linroid.kiff.container

import com.linroid.kiff.io.SeekableSource

/**
 * Checks that a [ContainerFormat] tiles what it decomposes.
 *
 * Exact tiling is the one rule a format cannot get away with breaking, and it is the one an
 * implementer gets wrong first - usually by naming the parts a format documents and forgetting the
 * padding between them. A patch restores byte for byte only because every byte belongs to some
 * region, so Kiff does not use a decomposition with a hole in it: the range is described whole, and
 * the format's work is quietly lost. This says why.
 *
 * Call it from a test over whatever inputs the format is meant to handle, including the awkward
 * ones: empty containers, containers with trailing bytes, containers that are almost but not quite
 * the format, and containers larger than memory, which a [SeekableSource] that makes its bytes up
 * as it is read can stand in for.
 */
public object ContainerFormatTiling {

  /** Complaints about how [format] decomposes `[from, to)` of [input]; empty means it tiles. */
  public fun check(
    format: ContainerFormat,
    input: SeekableSource,
    from: Long = 0,
    to: Long = input.size
  ): List<String> = problems(format.decompose(input, from, to), from, to)

  /** [check], as a single assertion message, or null when the format tiles correctly. */
  public fun describe(
    format: ContainerFormat,
    input: SeekableSource,
    from: Long = 0,
    to: Long = input.size
  ): String? {
    val problems = check(format, input, from, to)
    if (problems.isEmpty()) return null
    return "${format.name} does not tile [$from, $to): ${problems.joinToString("; ")}"
  }

  /** What is wrong with [children] as the parts of `[from, to)`; nothing, for no children. */
  internal fun problems(children: List<Child>, from: Long, to: Long): List<String> {
    if (children.isEmpty()) return emptyList()

    val problems = mutableListOf<String>()
    var cursor = from
    for (child in children) {
      if (child.from != cursor) {
        problems += if (child.from > cursor) {
          "gap of ${child.from - cursor} bytes at $cursor before '${child.name}'"
        } else {
          "'${child.name}' starts at ${child.from}, overlapping what ended at $cursor"
        }
      }
      if (child.to < child.from) {
        problems += "'${child.name}' ends at ${child.to}, before it starts at ${child.from}"
      }
      if (child.contentFrom < child.from || child.contentTo > child.to) {
        problems += "'${child.name}' has content outside its own extent"
      }
      cursor = child.to
    }
    if (cursor != to) {
      problems += if (cursor < to) {
        "${to - cursor} bytes at the end belong to no child"
      } else {
        "children run $cursor past the end at $to"
      }
    }
    return problems
  }

  /** True when [children] are the parts of `[from, to)` exactly, and so can be encoded. */
  internal fun tiles(children: List<Child>, from: Long, to: Long): Boolean {
    var cursor = from
    for (child in children) {
      if (child.from != cursor) return false
      cursor = child.to
    }
    return cursor == to
  }
}
