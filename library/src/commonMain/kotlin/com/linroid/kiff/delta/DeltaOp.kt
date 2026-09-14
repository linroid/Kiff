package com.linroid.kiff.delta

/**
 * Opcodes of the delta instruction stream, packed into the low two bits of a varint tag whose upper
 * bits carry the length: `tag = (length shl 2) or opcode`.
 */
internal object DeltaOp {
  const val ADD = 0
  const val COPY = 1
  const val RUN = 2
  const val END = 3

  /** Shortest match worth a COPY instruction instead of literal bytes. */
  const val MIN_MATCH = RollingHash.WINDOW

  /** Shortest repeated byte sequence worth a RUN instruction. */
  const val MIN_RUN = 16
}
