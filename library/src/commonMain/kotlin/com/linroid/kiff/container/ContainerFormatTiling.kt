package com.linroid.kiff.container

/**
 * Checks that a [ContainerFormat] tiles what it decomposes.
 *
 * Exact tiling is the one rule a format cannot get away with breaking, and it is the one an
 * implementer gets wrong first - usually by naming the parts a format documents and forgetting the
 * padding between them. A patch restores byte for byte only because every byte belongs to some
 * region, so a hole here becomes a patch that cannot restore, discovered much later.
 *
 * Call it from a test over whatever inputs the format is meant to handle, including the awkward
 * ones: empty containers, containers with trailing bytes, containers that are almost but not quite
 * the format.
 */
object ContainerFormatTiling {

  /** Complaints about [format]'s decomposition; empty means it tiles correctly. */
  fun check(
    format: ContainerFormat,
    bytes: ByteArray,
    from: Int = 0,
    to: Int = bytes.size
  ): List<String> {
    val children = format.decompose(bytes, from, to)
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

  /** [check], as a single assertion message, or null when the format tiles correctly. */
  fun describe(
    format: ContainerFormat,
    bytes: ByteArray,
    from: Int = 0,
    to: Int = bytes.size
  ): String? {
    val problems = check(format, bytes, from, to)
    if (problems.isEmpty()) return null
    return "${format.name} does not tile [$from, $to): ${problems.joinToString("; ")}"
  }
}
