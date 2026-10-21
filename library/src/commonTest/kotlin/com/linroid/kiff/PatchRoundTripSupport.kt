package com.linroid.kiff

import com.linroid.kiff.container.ApkFormat
import com.linroid.kiff.container.ZipFormat
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import com.linroid.kiff.region.RegionReport
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Builds patches the way [Kiff.createPatch] does, with [structure] in place of detection, so a test
 * can describe a target as another kind of file would be.
 */
internal class ForcedStructure(
  private val structure: Structure,
  private val options: KiffOptions = KiffOptions()
) {
  val id: PatcherId get() = structure.id
  val name: String get() = structure.name

  fun createPatch(source: SeekableSource, target: SeekableSource): ByteArray =
    PatchBuilder.build(source, target, options, structure = structure)

  fun createPatch(source: ByteArray, target: ByteArray): ByteArray =
    createPatch(source.asSource(), target.asSource())

  /** Builds the patch and reports where its bytes went, region by region. */
  fun explain(source: ByteArray, target: ByteArray): RegionReport =
    KiffReports.explainAs(source, target, options, structure)
}

/** Describes every target as plain bytes. */
internal fun asBytes(options: KiffOptions = KiffOptions()) =
  ForcedStructure(Structure.Bytes, options)

/** Describes every target as a zip, entries, gaps and directory, whatever it is. */
internal fun asZip(options: KiffOptions = KiffOptions()) =
  ForcedStructure(Structure.Archive(ZipFormat()), options)

/** Describes every target as an APK, with its dex files searched together. */
internal fun asApk(options: KiffOptions = KiffOptions()) =
  ForcedStructure(Structure.Archive(ApkFormat()), options)

/** Asserts that [builder]'s patch rebuilds [target] byte for byte, and reports its size. */
internal fun assertRestores(builder: ForcedStructure, source: ByteArray, target: ByteArray): Int {
  val patch = builder.createPatch(source, target)
  val info = Kiff.info(patch)
  assertEquals(builder.id, info.patcher)
  assertEquals(source.size.toLong(), info.sourceSize)
  assertEquals(target.size.toLong(), info.targetSize)
  assertContentEquals(target, Kiff.applyPatch(source, patch), "restored bytes differ")
  return patch.size
}

/** Pseudo-random but compressible bytes, closer to real file content than pure noise. */
fun structuredBytes(size: Int, seed: Int): ByteArray {
  val random = Random(seed)
  val result = ByteArray(size)
  var offset = 0
  while (offset < size) {
    when (random.nextInt(4)) {
      0 -> {
        val run = minOf(size - offset, random.nextInt(1, 64))
        result.fill(random.nextInt(256).toByte(), offset, offset + run)
        offset += run
      }
      1 -> {
        val phrase = "section_${random.nextInt(32)} ".encodeToByteArray()
        val count = minOf(size - offset, phrase.size)
        phrase.copyInto(result, offset, 0, count)
        offset += count
      }
      else -> {
        val chunk = minOf(size - offset, random.nextInt(1, 96))
        random.nextBytes(result, offset, offset + chunk)
        offset += chunk
      }
    }
  }
  return result
}
