package com.linroid.kiff

import com.linroid.kiff.io.SeekableSource

/**
 * A patcher: it turns a `(source, target)` pair into a self-describing patch and rebuilds the exact
 * target bytes from the source plus that patch.
 *
 * Patchers differ in how they carve the two files into regions worth comparing - the whole file, or
 * the entries of an archive - not in how those bytes are searched. The search itself is a
 * [com.linroid.kiff.delta.DeltaAlgorithm], and one algorithm serves every patcher.
 *
 * Inputs are [SeekableSource] rather than streams because a delta is not a sequential transform: a
 * copy instruction names an arbitrary source offset, and applying one reads it back. The
 * [ByteArray] overloads are conveniences over [com.linroid.kiff.io.ByteArraySource].
 */
interface Patcher {
  val id: PatcherId
  val name: String

  /** Returns a patch that rebuilds [target] from [source]. */
  fun createPatch(source: SeekableSource, target: SeekableSource): ByteArray

  /**
   * Rebuilds the target bytes from [source] and [patch].
   *
   * @throws KiffException.SourceMismatch if [source] is not the file the patch was built against.
   * @throws KiffException.VerificationFailed if the rebuilt bytes fail the recorded checksum.
   */
  fun applyPatch(source: SeekableSource, patch: ByteArray): ByteArray

  fun createPatch(source: ByteArray, target: ByteArray): ByteArray

  fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray
}
