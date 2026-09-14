package com.linroid.kiff

sealed class KiffException(message: String) : Exception(message) {

  /** The patch bytes are truncated, corrupt, or were written by an incompatible version. */
  class InvalidPatch(message: String) : KiffException(message)

  /** The source file is not the one the patch was generated against. */
  class SourceMismatch(message: String) : KiffException(message)

  /** The rebuilt target does not match the checksum recorded when the patch was created. */
  class VerificationFailed(message: String) : KiffException(message)

  /** The input is not a well-formed archive for the selected patcher. */
  class UnsupportedInput(message: String) : KiffException(message)
}
