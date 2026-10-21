@file:Suppress("DEPRECATION")

package com.linroid.kiff

import com.linroid.kiff.delta.DeltaAlgorithm
import com.linroid.kiff.delta.RollingHashAlgorithm

/**
 * Treats both files as opaque byte streams and describes the target as copies from anywhere in the
 * source plus literal and run-length fill.
 *
 * [Kiff.createPatch] does exactly this for a file it finds no structure in.
 */
@Deprecated(
  "Kiff.createPatch detects what a file is, and describes one it finds no structure in " +
    "exactly as this does. Patchers are removed in 0.3.0."
)
class BinaryPatcher(
  override val algorithm: DeltaAlgorithm = RollingHashAlgorithm
) : DeltaPatcher() {

  override val id: PatcherId = PatcherId.BINARY
  override val name: String = "binary"

  override val options: KiffOptions = KiffOptions(algorithm = algorithm)

  override val structure: Structure = Structure.Bytes
}
