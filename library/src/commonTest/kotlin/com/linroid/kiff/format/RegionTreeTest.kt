package com.linroid.kiff.format

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.ZipPatcher
import com.linroid.kiff.assertRestores
import com.linroid.kiff.region.BinaryRegionPlanner
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The tree is the patch. These are the claims that hold whatever a region turns out to contain:
 * it restores, it never costs more than the bytes, and a malformed tree is refused rather than
 * followed.
 */
class RegionTreeTest {

  private fun text(lines: Int, seed: Int) =
    (1..lines).joinToString("\n") { "entry $it value ${(it * seed) % 97}" } + "\n"

  @Test
  fun aTextEntryIsDiffedAsLinesAndRestoresExactly() {
    val original = text(400, seed = 3)
    val edited = original.replace("entry 200 ", "entry 200 changed ")
    val source = TestZipBuilder().entry("config.txt", original).build()
    val target = TestZipBuilder().entry("config.txt", edited).build()

    val size = assertRestores(Kiff.zip, source, target)
    assertTrue(
      size < original.length / 4,
      "one changed line should not cost a quarter of the file, got $size bytes"
    )
  }

  @Test
  fun offeringTheLineEncodingNeverCostsSize() {
    // The planner nominates; it does not decide. Whatever it shortlists, the encoder builds both
    // and keeps the smaller, so switching a planner on can never produce a larger patch than
    // leaving it off. Two shapes here: equal-length edits, which the byte search wins outright
    // with its difference encoding, and an insertion, which shifts every byte after it.
    val scattered = (1..600).joinToString("\n") { "l$it" } + "\n"
    val scatteredEdit = (1..600).joinToString("\n") { if (it % 3 == 0) "x$it" else "l$it" } + "\n"
    val long = text(600, seed = 5)
    val longEdit = long.replace("entry 3 ", "inserted line\nentry 3 ")

    for ((original, edited) in listOf(scattered to scatteredEdit, long to longEdit)) {
      val source = TestZipBuilder().entry("config.txt", original).build()
      val target = TestZipBuilder().entry("config.txt", edited).build()
      val offered = ZipPatcher().createPatch(source, target).size
      val bytesOnly = ZipPatcher(planner = BinaryRegionPlanner).createPatch(source, target).size
      assertTrue(
        offered <= bytesOnly,
        "offering the line encoding cost size: $offered against $bytesOnly"
      )
    }
  }

  @Test
  fun bothPlansRestoreTheSameBytes() {
    val original = text(300, seed = 7)
    val edited = original.replace("entry 150 ", "entry 150 edited ")
    val source = TestZipBuilder().entry("config.txt", original).build()
    val target = TestZipBuilder().entry("config.txt", edited).build()

    assertRestores(ZipPatcher(), source, target)
    assertRestores(ZipPatcher(planner = BinaryRegionPlanner), source, target)
  }

  @Test
  fun anUnrelatedEntryIsStoredRatherThanDeltaEncoded() {
    // Nothing in the source resembles it, so any delta would cost more than the bytes themselves.
    val source = TestZipBuilder().entry("a.bin", structuredBytes(40_000, seed = 20)).build()
    val target = TestZipBuilder().entry("a.bin", structuredBytes(40_000, seed = 21)).build()
    val size = assertRestores(Kiff.zip, source, target)
    assertTrue(
      size <= target.size + 64,
      "a patch must never cost more than storing the target, got $size for ${target.size}"
    )
  }

  @Test
  fun identicalArchivesCoalesceIntoOneCopy() {
    val archive = TestZipBuilder()
      .entry("a.bin", structuredBytes(20_000, seed = 30))
      .entry("b.bin", structuredBytes(20_000, seed = 31))
      .entry("c.bin", structuredBytes(20_000, seed = 32))
      .build()
    val size = assertRestores(Kiff.zip, archive, archive)
    assertTrue(size < 64, "identical archives should collapse to one copy, got $size bytes")
  }

  @Test
  fun aTreeNestedTooDeeplyIsRefused() {
    val out = ByteWriter(64)
    // A composite that holds only itself, deeper than any real file nests.
    repeat(MAX_REGION_DEPTH + 2) {
      out.writeVarLong(1)
      out.writeByte(RegionEncoding.COMPOSITE.code)
      out.writeVarInt(1)
    }
    val tree = out.toByteArray()

    val payload = ByteWriter(64)
    payload.writeVarInt(tree.size)
    payload.writeVarInt(0)
    payload.writeByte(0)
    payload.writeVarInt(0)
    payload.writeBytes(tree)

    val failure = assertFailsWith<KiffException.InvalidPatch> {
      PatchPayload.read(ByteArray(4), payload.toByteArray(), 0, 1)
    }
    assertTrue("nests" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun aRegionThatOverrunsTheTargetIsRefused() {
    val out = ByteWriter(64)
    out.writeVarLong(9_000)
    out.writeByte(RegionEncoding.RAW.code)
    val tree = out.toByteArray()

    val payload = ByteWriter(64)
    payload.writeVarInt(tree.size)
    payload.writeVarInt(0)
    payload.writeByte(0)
    payload.writeVarInt(0)
    payload.writeBytes(tree)

    assertFailsWith<KiffException.InvalidPatch> {
      PatchPayload.read(ByteArray(4), payload.toByteArray(), 0, 10)
    }
  }
}
