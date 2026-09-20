package com.linroid.kiff.region

/**
 * What a region is *for*, which is what makes an attribution readable across formats.
 *
 * The vocabulary is deliberately format-neutral: a zip entry's record and a dex section's items are
 * both [CONTENT], a central directory and a dex map list are both [INDEX]. A patcher that learns to
 * carve a new format up reuses these rather than inventing its own.
 */
enum class RegionKind {
  /** Payload the format names: a zip entry's record, a dex section's items. */
  CONTENT,

  /** Structure the format keeps in order to find its content: a central directory, a map list. */
  INDEX,

  /** Bytes that belong to nothing named: a preamble, alignment padding, an APK signing block. */
  GAP,

  /** The whole input, from a patcher that does not carve it up at all. */
  WHOLE
}

/**
 * What one region of the target cost in the patch.
 *
 * The split matters: [literalBytes] is content the patch had to carry because it exists nowhere in
 * the source, while [instructionBytes] is the cost of *saying* where bytes come from. A region
 * copied wholesale still costs a COPY instruction - a few bytes - which is why cheap is not free,
 * and why an archive of many unchanged entries has a floor no delta algorithm can go below.
 */
data class RegionCost(
  val name: String,
  val kind: RegionKind,
  /** Bytes of the target this region covers. */
  val targetBytes: Long,
  /** Bytes of instruction stream spent describing this region. */
  val instructionBytes: Long,
  /** Bytes of literal content this region contributed, before the literal stream is packed. */
  val literalBytes: Long
) {
  /** Everything the region added to the delta. */
  val patchBytes: Long get() = instructionBytes + literalBytes

  /** Patch bytes per target byte; near 0.0 for a region the patch merely points at. */
  val ratio: Double get() = if (targetBytes == 0L) 0.0 else patchBytes.toDouble() / targetBytes

  /** True when the region carried no content of its own, only references into the source. */
  val carriedNothing: Boolean get() = literalBytes == 0L
}

/**
 * Where a patch's bytes went, region by region.
 *
 * A region's cost is measured as the delta stream's growth while that region is encoded, so the
 * figures are taken *before* the literal stream is packed and add up to somewhat more than
 * [patchSize]. What they are for is the shape of the distribution - which parts of a file a patch
 * is actually spent on - not an exact accounting of the compressed bytes.
 */
data class RegionReport(
  val regions: List<RegionCost>,
  val targetSize: Long,
  val patchSize: Long
) {
  /** Total attributed bytes, i.e. the delta before its literal stream is packed. */
  val attributedBytes: Long get() = regions.sumOf { it.patchBytes }

  /** What it cost merely to say where bytes come from, across every region. */
  val instructionBytes: Long get() = regions.sumOf { it.instructionBytes }

  /** Content the patch had to carry, across every region. */
  val literalBytes: Long get() = regions.sumOf { it.literalBytes }

  /** Regions that carried content, most expensive first. */
  val costly: List<RegionCost>
    get() = regions.filter { it.literalBytes > 0 }.sortedByDescending { it.patchBytes }

  /** Regions the patch only pointed at, paying an instruction and no content. */
  val referenced: List<RegionCost> get() = regions.filter { it.carriedNothing }

  fun bytes(kind: RegionKind): Long =
    regions.filter { it.kind == kind }.sumOf { it.patchBytes }
}

/**
 * Told what each target region cost while a patch is being built.
 *
 * Recording happens around the existing encode rather than inside it, so measuring a patch cannot
 * change the patch: the bytes reported are the same bytes the encoder would have written anyway.
 */
internal fun interface RegionRecorder {
  fun record(
    name: String,
    kind: RegionKind,
    targetBytes: Long,
    instructionBytes: Long,
    literalBytes: Long
  )
}

/** Label for the single region a patcher reports when it scans an input whole. */
internal const val WHOLE_FILE_REGION = "(whole file)"
