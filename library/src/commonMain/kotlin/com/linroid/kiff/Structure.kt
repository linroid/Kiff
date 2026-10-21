package com.linroid.kiff

import com.linroid.kiff.apk.looksLikeApk
import com.linroid.kiff.container.ApkFormat
import com.linroid.kiff.container.ContainerFormat
import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.container.ZipFormat

/**
 * What a target is taken to be as a whole: it decides how the file is described, and which code
 * the header records.
 *
 * The header can only say binary, zip or apk - those are the codes version 1 has - so a format
 * added to the registry records binary, as Kiff 0.1.0 did for every file that was not a zip.
 */
internal sealed class Structure(val id: PatcherId, val name: String) {

  /** One search over the whole file. */
  data object Bytes : Structure(PatcherId.BINARY, "bytes")

  /**
   * An archive, always taken apart, as the zip and apk patchers always did. Its entries are where
   * nearly all of the saving is, and weighing it would mean a second search of the whole file -
   * which, for an archive, is the largest input Kiff is ever given.
   */
  class Archive(val format: ZipFormat) : Structure(
    if (format is ApkFormat) PatcherId.APK else PatcherId.ZIP,
    format.name
  )

  /**
   * Any other registered format, taken apart only when that makes the smaller patch. Decomposing
   * is a bet, here as anywhere inside a file, so the file is described both ways and the plain
   * byte search is kept unless the format beats it.
   */
  class Weighed(val format: ContainerFormat) : Structure(PatcherId.BINARY, format.name)

  companion object {

    /**
     * Picks the structure for [target] from [registry].
     *
     * Plain [ZipFormat] is how an APK is found too. An APK is a zip in every way that decides
     * whether it can be read, and what an APK adds - grouping its dex files - is knowledge about
     * the file being patched, so it is only applied when that file carries an
     * `AndroidManifest.xml`. A subclass of [ZipFormat] is used as it is.
     */
    fun detect(target: ByteArray, registry: ContainerRegistry): Structure {
      val format = registry.formatOfFile(target) ?: return Bytes
      if (format !is ZipFormat) return Weighed(format)
      val apk = format::class == ZipFormat::class && looksLikeApk(target)
      return Archive(if (apk) ApkFormat() else format)
    }
  }
}
