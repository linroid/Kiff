package com.linroid.kiff

import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.io.KiffFiles
import com.linroid.kiff.io.fileSource
import okio.IOException

/** Entry point: the bundled patchers plus file-level create/apply helpers. */
object Kiff {

  val binary: BinaryPatcher = BinaryPatcher()

  val zip: ZipPatcher = ZipPatcher()

  val apk: ApkPatcher = ApkPatcher()

  val patchers: List<Patcher> = listOf(binary, zip, apk)

  fun patcher(id: PatcherId): Patcher = patchers.first { it.id == id }

  fun patcherOrNull(name: String): Patcher? =
    patchers.firstOrNull { it.name.equals(name, ignoreCase = true) }

  /** Reads the header of [patch] without applying it. */
  fun info(patch: ByteArray): PatchInfo =
    infoOf(PatchFormat.readHeader(ByteReader(patch)), patch.size.toLong())

  /**
   * Reads the header of the patch file at [patchPath], and nothing past it.
   *
   * A header is a few dozen bytes however large the patch, so this costs the same for any file -
   * including one that turns out not to be a patch at all.
   */
  fun info(patchPath: String): PatchInfo {
    val prefix = KiffFiles.readPrefix(patchPath, PatchFormat.LONGEST_HEADER)
    val header = PatchFormat.readHeader(ByteReader(prefix))
    return infoOf(header, KiffFiles.size(patchPath) ?: prefix.size.toLong())
  }

  private fun infoOf(header: PatchFormat.Header, patchSize: Long) = PatchInfo(
    patcher = header.patcher,
    formatVersion = header.version,
    sourceSize = header.sourceSize,
    sourceCrc32 = header.sourceCrc32,
    targetSize = header.targetSize,
    targetCrc32 = header.targetCrc32,
    patchSize = patchSize
  )

  /**
   * Writes a patch that rebuilds [targetPath] from [sourcePath].
   *
   * Each file is read into memory once - the search indexes arrays, so inputs over 2 GB are
   * refused - and its checksum is taken from the same bytes. [patchPath] may not name either
   * input.
   */
  fun createPatch(
    patcher: Patcher,
    sourcePath: String,
    targetPath: String,
    patchPath: String
  ): PatchInfo {
    // The patch is written once both inputs are read, so a patch path naming one of them would
    // replace the very file the patch is for - the source, which it needs to be applied.
    for ((input, role) in listOf(sourcePath to "source", targetPath to "target")) {
      if (KiffFiles.sameFile(patchPath, input)) {
        throw IOException("Cannot write $patchPath: it is the $role")
      }
    }
    val patch = fileSource(sourcePath).use { source ->
      fileSource(targetPath).use { target -> patcher.createPatch(source, target) }
    }
    KiffFiles.writeBytes(patchPath, patch)
    return info(patch)
  }

  /**
   * Restores a file from [sourcePath] and [patchPath] into [outputPath], using whichever patcher
   * created the patch.
   *
   * [outputPath] is replaced only by a restore that verified: a refused patch leaves it as it was.
   * That also makes it safe to name the source itself, to update a file in place; the file keeps
   * its permissions, and a symlink is followed to the file it names.
   */
  fun applyPatch(sourcePath: String, patchPath: String, outputPath: String): PatchInfo {
    val patch = KiffFiles.readBytes(patchPath)
    val patchInfo = info(patch)
    // Neither file is held: the source is addressed on disk and the restore goes straight to the
    // output, so this costs the same whether the package is eight megabytes or eight hundred. The
    // source is closed before the output is moved into place, which may be over the source.
    KiffFiles.writeStreaming(outputPath) { target ->
      fileSource(sourcePath).use { source ->
        patcher(patchInfo.patcher).applyPatch(source, patch, target)
      }
    }
    return patchInfo
  }
}
