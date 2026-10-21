package com.linroid.kiff

import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.SeekableSource

/**
 * A patcher: it turns a `(source, target)` pair into a self-describing patch and rebuilds the exact
 * target bytes from the source plus that patch.
 *
 * Patchers differ only in what they take the target to be as a whole - plain bytes, a zip or an
 * APK - which [Kiff.createPatch] now works out from the file itself. Every patcher applies every
 * patch, the same way [Kiff.applyPatch] does.
 */
@Deprecated(
  "Kiff.createPatch detects what a file is, and Kiff.applyPatch applies any patch. " +
    "Patchers are removed in 0.3.0."
)
interface Patcher {
  val id: PatcherId
  val name: String

  /** Returns a patch that rebuilds [target] from [source]. */
  @Deprecated(
    "Kiff.createPatch detects what the target is, so on an archive it may describe it differently.",
    ReplaceWith("Kiff.createPatch(source, target)", "com.linroid.kiff.Kiff")
  )
  fun createPatch(source: SeekableSource, target: SeekableSource): ByteArray

  /**
   * Rebuilds the target bytes from [source] and [patch].
   *
   * Any patcher applies any patch, whichever one built it: they differ only in how they describe a
   * target, and every patch is read the same way.
   *
   * @throws KiffException.InvalidPatch if the patch is malformed.
   * @throws KiffException.SourceMismatch if [source] is not the file the patch was built against.
   * @throws KiffException.VerificationFailed if the rebuilt bytes fail the recorded checksum.
   */
  @Deprecated(
    "Kiff.applyPatch applies any patch.",
    ReplaceWith("Kiff.applyPatch(source, patch)", "com.linroid.kiff.Kiff")
  )
  fun applyPatch(source: SeekableSource, patch: ByteArray): ByteArray

  /**
   * Rebuilds the target into [target] rather than returning it.
   *
   * [target] has received every byte before the final checksum is compared, so treat what it holds
   * as unverified until this returns, and discard it if this throws. See [RestoreTarget].
   */
  @Deprecated(
    "Kiff.applyPatch applies any patch.",
    ReplaceWith("Kiff.applyPatch(source, patch, target)", "com.linroid.kiff.Kiff")
  )
  fun applyPatch(source: SeekableSource, patch: ByteArray, target: RestoreTarget)

  @Deprecated(
    "Kiff.createPatch detects what the target is, so on an archive it may describe it differently.",
    ReplaceWith("Kiff.createPatch(source, target)", "com.linroid.kiff.Kiff")
  )
  fun createPatch(source: ByteArray, target: ByteArray): ByteArray

  @Deprecated(
    "Kiff.applyPatch applies any patch.",
    ReplaceWith("Kiff.applyPatch(source, patch)", "com.linroid.kiff.Kiff")
  )
  fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray
}
