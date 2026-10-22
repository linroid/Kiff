package com.linroid.kiff.delta

/**
 * Opcodes of the delta instruction stream, packed into the low three bits of a varint tag whose
 * upper bits carry the length: `tag = (length shl SHIFT) or opcode`.
 */
internal object DeltaOp {
  const val END = 0
  const val ADD = 1
  const val COPY = 2
  const val RUN = 3
  const val DIFF = 4

  const val SHIFT = 3
  const val MASK = 0x7L

  /** Shortest match worth a COPY instruction instead of literal bytes. */
  const val MIN_MATCH = RollingHash.WINDOW

  /** Shortest repeated byte sequence worth a RUN instruction. */
  const val MIN_RUN = 16

  /** Shortest almost-matching region worth a DIFF instruction. */
  const val MIN_DIFF = 32

  /**
   * Shortest stretch of a DIFF region that equals the source exactly and is written as a COPY.
   *
   * Left in the DIFF, such a stretch is that many zero differences, which the reader unpacks and
   * holds in memory: 104 MiB of them between two 110 MiB R8 mapping files that barely differ, at
   * 12 bits for every 4,119 bytes once packed. Split out at 1 KiB, that patch was 31% smaller and
   * took a tenth of the memory to apply, and patches between release APKs stayed as they were. 16
   * to 64 bytes took 2-3.6% more off mapping files but made APK patches up to 3% larger. Each split
   * costs a COPY and a second DIFF, a few bytes, where a short run of zeroes packs to almost
   * nothing, which is also why point edits a few KiB apart can come out slightly larger even here.
   */
  const val MIN_COPY_IN_DIFF = 1024

  /** DIFF regions grow one chunk at a time, scored on how many bytes in the chunk agree. */
  const val DIFF_CHUNK = 32
  const val DIFF_EQUAL_TO_START = 20
  const val DIFF_EQUAL_TO_CONTINUE = 16
}
