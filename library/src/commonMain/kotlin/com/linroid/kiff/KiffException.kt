package com.linroid.kiff

/**
 * A verdict Kiff reached about the input it was handed: the patch is malformed, the source is not
 * the one it was built from, the result failed its checksum, or the input is one Kiff cannot work
 * with.
 *
 * The kinds below are not a closed set. A minor release may add one, for a failure that today is
 * reported as one of these or not at all, so a `when` over a [KiffException] keeps an `else`
 * branch:
 *
 * ```kotlin
 * when (e) {
 *   is KiffException.SourceMismatch -> askForTheRightFile()
 *   else -> report(e.message)
 * }
 * ```
 *
 * Only Kiff defines kinds; the class cannot be extended elsewhere.
 */
public abstract class KiffException internal constructor(message: String) : Exception(message) {

  /** The patch bytes are truncated, corrupt, or were written by an incompatible version. */
  public class InvalidPatch(message: String) : KiffException(message)

  /** The source file is not the one the patch was generated against. */
  public class SourceMismatch(message: String) : KiffException(message)

  /** The rebuilt target does not match the checksum recorded when the patch was created. */
  public class VerificationFailed(message: String) : KiffException(message)

  /** An input Kiff cannot work with: too large to index, or not the archive a report needs. */
  public class UnsupportedInput(message: String) : KiffException(message)
}
