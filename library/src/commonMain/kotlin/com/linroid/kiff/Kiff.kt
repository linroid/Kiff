package com.linroid.kiff

import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.io.KiffFiles

/** Entry point: the bundled algorithms plus file-level create/apply helpers. */
object Kiff {

  val binary: PatchAlgorithm = BinaryDiff()

  val zip: ZipDiff = ZipDiff()

  val algorithms: List<PatchAlgorithm> = listOf(binary, zip)

  fun algorithm(id: AlgorithmId): PatchAlgorithm = algorithms.first { it.id == id }

  fun algorithmOrNull(name: String): PatchAlgorithm? =
    algorithms.firstOrNull { it.name.equals(name, ignoreCase = true) }

  /** Reads the header of [patch] without applying it. */
  fun info(patch: ByteArray): PatchInfo {
    val header = PatchFormat.readHeader(ByteReader(patch))
    return PatchInfo(
      algorithm = header.algorithm,
      formatVersion = header.version,
      sourceSize = header.sourceSize,
      sourceCrc32 = header.sourceCrc32,
      targetSize = header.targetSize,
      targetCrc32 = header.targetCrc32,
      patchSize = patch.size
    )
  }

  /** Writes a patch that rebuilds [targetPath] from [sourcePath]. */
  fun createPatch(
    algorithm: PatchAlgorithm,
    sourcePath: String,
    targetPath: String,
    patchPath: String
  ): PatchInfo {
    val patch = algorithm.createPatch(
      KiffFiles.readBytes(sourcePath),
      KiffFiles.readBytes(targetPath)
    )
    KiffFiles.writeBytes(patchPath, patch)
    return info(patch)
  }

  /**
   * Restores a file from [sourcePath] and [patchPath] into [outputPath], using whichever algorithm
   * created the patch.
   */
  fun applyPatch(sourcePath: String, patchPath: String, outputPath: String): PatchInfo {
    val patch = KiffFiles.readBytes(patchPath)
    val patchInfo = info(patch)
    val restored = algorithm(patchInfo.algorithm)
      .applyPatch(KiffFiles.readBytes(sourcePath), patch)
    KiffFiles.writeBytes(outputPath, restored)
    return patchInfo
  }
}
