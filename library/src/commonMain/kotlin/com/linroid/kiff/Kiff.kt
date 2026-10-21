package com.linroid.kiff

import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.io.KiffFiles
import com.linroid.kiff.io.RestoreTarget
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.io.fileSource
import okio.IOException

/**
 * Entry point: creates a patch that turns one file into another, and applies one.
 *
 * Creating looks at what the target is as a whole and describes it accordingly: an Android package
 * entry by entry with its dex files searched together, any other zip entry by entry, and anything
 * else as plain bytes. Inside an archive, archives and dex files are taken apart where that makes
 * the patch smaller. [KiffOptions.containers] says which formats are looked for, at either level.
 *
 * Applying needs none of that: a patch records the structure it used, so every patch is applied the
 * same way.
 *
 * Inputs are [SeekableSource] rather than streams because a delta is not a sequential transform: a
 * copy instruction names an arbitrary source offset, and applying one reads it back. The
 * [ByteArray] overloads are conveniences over [com.linroid.kiff.io.ByteArraySource].
 */
object Kiff {

  /**
   * The [PatchInfo.formatVersion]s this build can read; every patch it writes is one of them.
   *
   * An updater offered patches in several formats - a manifest names each one's - can pass over
   * those it would refuse before downloading them, rather than after.
   */
  val readableFormats: Set<Int> = PatchFormat.SUPPORTED

  /**
   * Returns a patch that rebuilds [target] from [source].
   *
   * Both inputs are read into memory once - the search indexes arrays, so inputs over 2 GB are
   * refused - and their checksums are taken from the same bytes.
   */
  fun createPatch(
    source: SeekableSource,
    target: SeekableSource,
    options: KiffOptions = KiffOptions()
  ): ByteArray = PatchBuilder.build(source, target, options)

  fun createPatch(
    source: ByteArray,
    target: ByteArray,
    options: KiffOptions = KiffOptions()
  ): ByteArray = createPatch(source.asSource(), target.asSource(), options)

  /**
   * Writes a patch that rebuilds [targetPath] from [sourcePath].
   *
   * Each file is read into memory once - the search indexes arrays, so inputs over 2 GB are
   * refused - and its checksum is taken from the same bytes. [patchPath] may not name either
   * input.
   */
  fun createPatch(
    sourcePath: String,
    targetPath: String,
    patchPath: String,
    options: KiffOptions = KiffOptions()
  ): PatchInfo = writePatch(sourcePath, targetPath, patchPath) { source, target ->
    createPatch(source, target, options)
  }

  /**
   * Rebuilds the target bytes from [source] and [patch].
   *
   * The result is held whole, at the size the patch's own header declares - and a patch of a few
   * dozen bytes can validly declare gigabytes. For a patch you did not make, check
   * [info]`(patch).targetSize` against what you expect first, or restore into a [RestoreTarget].
   *
   * @throws KiffException.InvalidPatch if the patch is malformed.
   * @throws KiffException.SourceMismatch if [source] is not the file the patch was built against.
   * @throws KiffException.VerificationFailed if the rebuilt bytes fail the recorded checksum.
   */
  fun applyPatch(source: SeekableSource, patch: ByteArray): ByteArray =
    PatchRestorer.apply(source, patch)

  /**
   * Rebuilds the target into [target] rather than returning it.
   *
   * A patch describes its target in order, so a restore never needs the result in memory - only
   * somewhere to put it. Prefer this wherever the result is large or the machine is small, which
   * is most places a patch is actually applied.
   *
   * [target] has received every byte before the final checksum is compared, so treat what it holds
   * as unverified until this returns, and discard it if this throws. See [RestoreTarget].
   *
   * @throws KiffException.InvalidPatch if the patch is malformed.
   * @throws KiffException.SourceMismatch if [source] is not the file the patch was built against.
   * @throws KiffException.VerificationFailed if the rebuilt bytes fail a recorded checksum.
   */
  fun applyPatch(source: SeekableSource, patch: ByteArray, target: RestoreTarget) {
    PatchRestorer.apply(source, patch, target)
  }

  fun applyPatch(source: ByteArray, patch: ByteArray): ByteArray =
    applyPatch(source.asSource(), patch)

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
   * Restores a file from [sourcePath] and [patchPath] into [outputPath].
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
      fileSource(sourcePath).use { source -> applyPatch(source, patch, target) }
    }
    return patchInfo
  }

  private inline fun writePatch(
    sourcePath: String,
    targetPath: String,
    patchPath: String,
    create: (SeekableSource, SeekableSource) -> ByteArray
  ): PatchInfo {
    // The patch is written once both inputs are read, so a patch path naming one of them would
    // replace the very file the patch is for - the source, which it needs to be applied.
    for ((input, role) in listOf(sourcePath to "source", targetPath to "target")) {
      if (KiffFiles.sameFile(patchPath, input)) {
        throw IOException("Cannot write $patchPath: it is the $role")
      }
    }
    val patch = fileSource(sourcePath).use { source ->
      fileSource(targetPath).use { target -> create(source, target) }
    }
    KiffFiles.writeBytes(patchPath, patch)
    return info(patch)
  }
}
