package com.linroid.kiff.region

/**
 * How one leaf region's bytes get described.
 *
 * This is the choice the region tree exists to make possible. A container format decides what the
 * regions *are*; a plan decides, per region, which encoding suits the bytes inside it - so the
 * manifest in an archive can be diffed as lines while the native library beside it is diffed as
 * bytes, in the same patch.
 *
 * A minor release may add a choice, so a `when` over these keeps an `else` branch.
 */
public enum class RegionAlgorithm {
  /** The delta instruction set: a byte-level search over the source. Suits anything. */
  BINARY,

  /** A line-level edit script. Only correct for regions that really are text. */
  TEXT,

  /** The bytes verbatim. The floor: never larger than the region, never smaller either. */
  RAW
}

/** What a planner is told about a region before it chooses. */
public data class RegionInfo(
  /** Entry name, or a bracketed label for a region the format does not name. */
  val name: String,
  val kind: RegionKind,
  val targetBytes: Long,
  /** Bytes of the counterpart this region will be described against; 0 when it has none. */
  val sourceBytes: Long,
  /**
   * True when neither side holds a NUL byte.
   *
   * A cheap, deliberately conservative test: text never contains one and most binary formats do
   * within the first few hundred bytes. It is a necessary condition for [RegionAlgorithm.TEXT],
   * never a sufficient one - a planner is free to ignore it and go by name.
   */
  val looksLikeText: Boolean
)

/**
 * Chooses an encoding per leaf region.
 *
 * A plan is a hint, not a instruction: whatever it returns, the encoder still falls back to
 * [RegionAlgorithm.RAW] when the encoding it asked for came out larger than the bytes themselves,
 * so a bad guess costs size it could not have saved rather than a bigger patch.
 */
public fun interface RegionPlanner {
  public fun plan(region: RegionInfo): RegionAlgorithm
}

/**
 * Byte-level for everything, which is how every region was described before regions could choose.
 */
public val BinaryRegionPlanner: RegionPlanner = RegionPlanner { RegionAlgorithm.BINARY }

/**
 * Line-level for regions that look like text and are small enough to be worth it, byte-level
 * otherwise.
 *
 * The size cap is not about correctness. The line search takes O((N+M)D) time in the number of
 * lines, so it is the wrong tool for a large region however text-like it looks; past the cap the
 * byte-level search is both faster and, on that much data, usually smaller too.
 */
public val TextAwareRegionPlanner: RegionPlanner = RegionPlanner { region ->
  val withinCap = region.targetBytes <= TEXT_REGION_LIMIT && region.sourceBytes <= TEXT_REGION_LIMIT
  if (region.looksLikeText && withinCap && region.sourceBytes > 0) {
    RegionAlgorithm.TEXT
  } else {
    RegionAlgorithm.BINARY
  }
}

/** Largest region [TextAwareRegionPlanner] will hand to the line search. */
internal const val TEXT_REGION_LIMIT: Long = 1L shl 20
