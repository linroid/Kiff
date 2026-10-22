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
   * Shortest stretch of a DIFF that equals the source exactly, written as a COPY instead.
   *
   * Splitting one out costs a COPY and a second DIFF instruction, a few bytes, where a short run of
   * zeros packs to less: on release APKs and R8 mapping files, splitting at 16 bytes made APK
   * patches up to 3% larger. From 1 KiB to 16 KiB none got larger, and 1 KiB took the most off the
   * mapping files, and off the memory it takes to apply their patches.
   */
  const val MIN_EQUAL_IN_DIFF = 1024

  /** DIFF regions grow one chunk at a time, scored on how many bytes in the chunk agree. */
  const val DIFF_CHUNK = 32
  const val DIFF_EQUAL_TO_START = 20
  const val DIFF_EQUAL_TO_CONTINUE = 16
}
