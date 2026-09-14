package com.linroid.kiff

import com.linroid.kiff.internal.ByteReader
import com.linroid.kiff.io.KiffFiles

/** Entry point: the bundled patchers plus file-level create/apply helpers. */
object Kiff {

  val binary: Patcher = BinaryDiff()

  val zip: ZipDiff = ZipDiff()

  val apk: ApkDiff = ApkDiff()

  val patchers: List<Patcher> = listOf(binary, zip, apk)

  fun patcher(id: PatcherId): Patcher = patchers.first { it.id == id }

  fun patcherOrNull(name: String): Patcher? =
    patchers.firstOrNull { it.name.equals(name, ignoreCase = true) }

  /** Reads the header of [patch] without applying it. */
  fun info(patch: ByteArray): PatchInfo {
    val header = PatchFormat.readHeader(ByteReader(patch))
    return PatchInfo(
      patcher = header.patcher,
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
    patcher: Patcher,
    sourcePath: String,
    targetPath: String,
    patchPath: String
  ): PatchInfo {
    val patch = patcher.createPatch(
      KiffFiles.readBytes(sourcePath),
      KiffFiles.readBytes(targetPath)
    )
    KiffFiles.writeBytes(patchPath, patch)
    return info(patch)
  }

  /**
   * Restores a file from [sourcePath] and [patchPath] into [outputPath], using whichever patcher
   * created the patch.
   */
  fun applyPatch(sourcePath: String, patchPath: String, outputPath: String): PatchInfo {
    val patch = KiffFiles.readBytes(patchPath)
    val patchInfo = info(patch)
    val restored = patcher(patchInfo.patcher)
      .applyPatch(KiffFiles.readBytes(sourcePath), patch)
    KiffFiles.writeBytes(outputPath, restored)
    return patchInfo
  }
}
