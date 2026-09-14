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

  /** DIFF regions grow one chunk at a time, scored on how many bytes in the chunk agree. */
  const val DIFF_CHUNK = 32
  const val DIFF_EQUAL_TO_START = 20
  const val DIFF_EQUAL_TO_CONTINUE = 16
}
