package com.linroid.kiff

/**
 * A file-level diff algorithm: it turns a `(source, target)` pair into a self-describing patch and
 * rebuilds the exact target bytes from the source plus that patch.
 */
interface PatchAlgorithm {
  val id: AlgorithmId
  val name: String

  /** Returns a patch that rebuilds [target] from [source]. */
  fun createPatch(source: ByteArray, target: ByteArray): ByteArray

  /**
   * Rebuilds the target bytes from [source] and [patch].
   *
   * @throws KiffException.SourceMismatch if [source] is not the file the patch was built against.
   * @throws KiffException.VerificationFailed if the rebuilt bytes fail the recorded checksum.
   */
  fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray
}
