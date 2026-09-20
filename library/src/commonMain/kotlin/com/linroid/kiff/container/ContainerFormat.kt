package com.linroid.kiff.container

import com.linroid.kiff.region.RegionKind

/**
 * How a container is taken apart.
 *
 * This is the extension point for file formats. Implement it, add it to a [ContainerRegistry], and
 * patches start describing that format structurally - a dex as its sections rather than as one
 * opaque run of bytes, a tar as its members. Nothing else moves: the node encodings a patch is
 * built from are fixed, so a decoder that has never heard of the format still applies the patch,
 * and patches written before the format existed still apply too.
 *
 * That works because a patch records the structure it used rather than asking the decoder to work
 * it out. Decomposition is therefore an encode-side heuristic and nothing more, free to improve,
 * free to be wrong, free to be skipped when it does not pay.
 */
interface ContainerFormat {

  /** Short identifier, for diagnostics; it is never recorded in a patch. */
  val name: String

  /**
   * Whether `bytes[from, to)` is this format.
   *
   * Cheap checks only - a magic number, a trailer - because this is asked of every region that
   * might be a container, including ones that plainly are not.
   */
  fun detect(bytes: ByteArray, from: Int, to: Int): Boolean

  /**
   * The parts of `bytes[from, to)`, in order.
   *
   * **The children must tile the range exactly**: the first starts at [from], the last ends at
   * [to], each begins where the previous ended, and none overlap. Everything the format does not
   * name is still a child - a preamble, alignment padding, an APK signing block, a trailer - just
   * an unnamed one. A patch can only restore a file byte for byte if every byte belongs to
   * something, so a format that leaves a hole produces patches that cannot restore.
   *
   * [ContainerFormatTiling] asserts this for any implementation; use it in your tests.
   *
   * Answering an empty list means "on reflection, not mine": the range is then described as an
   * ordinary leaf. Returning a single child spanning the whole range is not useful and is treated
   * the same way.
   */
  fun decompose(bytes: ByteArray, from: Int, to: Int): List<Child>

  /**
   * Last-resort pairing for a target child that matched no source child by name or by content.
   *
   * The driver already pairs by name and then by content, which is right for most formats. This is
   * where a format contributes what only it knows - that `classes4.dex` belongs next to
   * `classes3.dex` when a build renumbers them.
   */
  fun pairUnmatched(target: Child, source: List<Child>): Child? = null
}

/** How a child's payload is stored inside its container. */
enum class Storage {
  /** Payload bytes are the real bytes: they can be compared, and recursed into. */
  STORED,

  /**
   * Payload is compressed.
   *
   * Comparing it means decoding it, and restoring it means re-encoding to the identical byte
   * stream, which nothing here can do yet. Such a child is described as an opaque leaf - correct,
   * just no smaller than the change in its compressed bytes.
   */
  DEFLATED
}

/**
 * One part of a container.
 *
 * [from]..[to] is everything the child owns. [contentFrom]..[contentTo] is the payload inside it,
 * which may be narrower when the format wraps the payload in framing - a zip's local file header
 * before the data, its optional descriptor after. The split matters because only the payload is
 * worth probing as a container of its own, and only the payload should get to choose an encoding
 * on its own merits; the framing around it is compared as the bytes it is.
 */
data class Child(
  val name: String,
  val kind: RegionKind,
  val from: Int,
  val to: Int,
  val contentFrom: Int = from,
  val contentTo: Int = to,
  val storage: Storage = Storage.STORED,
  /**
   * Identity for pairing, when the format can supply one that is cheaper or better than comparing
   * bytes - a zip's CRC and size, say. Children with equal keys are considered the same content.
   */
  val contentKey: Any? = null,
  /**
   * Children that content migrates between, and so should be searched against one another.
   *
   * Pairing decides which source child a target child is *described against*; this decides which
   * source bytes it can be *found in*, which is a different question whenever a format is free to
   * move content between siblings. Android's is: the classes in an APK are split across
   * `classes.dex`, `classes2.dex` and so on, and a rebuild repartitions them, so a class can leave
   * one dex for another while changing not at all. Without a group, those bytes sit in the source
   * file unreachable, because the search only ever indexed the counterpart.
   *
   * Members of a group are indexed together, over the span from the first to the last of them.
   * That span may take in unrelated bytes lying between; it costs index size and can only help.
   */
  val group: String? = null,
  /**
   * The byte widths of each field in a row, when this child is a table of fixed-width rows.
   *
   * Only the format knows that a region is a table, and the difference it makes is large: a table
   * of ids renumbers wholesale between versions and so cannot be matched at all as it stands,
   * while the gaps between its entries barely move. Declaring the layout lets the encoder try
   * reading it in columns of differences instead. Null for anything that is not a table.
   */
  val columns: List<Int>? = null
) {
  val size: Int get() = to - from

  /** True when this child is nothing but its payload, with no framing around it. */
  val isBare: Boolean get() = contentFrom == from && contentTo == to

  init {
    require(from <= contentFrom && contentFrom <= contentTo && contentTo <= to) {
      "Child $name has content [$contentFrom, $contentTo) outside its extent [$from, $to)"
    }
  }
}

/**
 * The formats a patcher will try, in order; the first that detects a range wins.
 *
 * Order is the only precedence there is, so put the specific before the general - an APK is a zip,
 * and whichever is asked first is the one that gets to decompose it.
 */
class ContainerRegistry(private val formats: List<ContainerFormat>) {

  constructor(vararg formats: ContainerFormat) : this(formats.toList())

  fun formatFor(bytes: ByteArray, from: Int, to: Int): ContainerFormat? =
    formats.firstOrNull { it.detect(bytes, from, to) }

  operator fun plus(format: ContainerFormat) = ContainerRegistry(formats + format)

  companion object {
    /** No formats: every input is one opaque leaf. */
    val Empty = ContainerRegistry(emptyList())
  }
}
