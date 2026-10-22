package com.linroid.kiff

import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.container.TestDexBuilder
import com.linroid.kiff.container.TestSectionFormat
import com.linroid.kiff.format.Crc32
import com.linroid.kiff.format.unpacked
import com.linroid.kiff.format.withStoredTree
import com.linroid.kiff.region.RegionKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureDetectionTest {

  /** A patch the pair's patcher writes, unpacked, by size and CRC-32 rather than whole. */
  private class Golden(val name: String, val size: Int, val crc32: UInt)

  /**
   * What the patcher each pair names wrote for it before detection existed, with its tree and
   * content unpacked. Equal here means byte for byte the same regions and the same bytes in them,
   * which is everything detection decides; how tightly they pack is the codec's business, and it
   * has packed them tighter since. Eight were captured again when the delta writer began copying
   * the long exact stretches inside a DIFF rather than storing zeroes for them, which detection has
   * no part in either.
   */
  private val golden = listOf(
    Golden("a rebuilt apk", 10620, 0x47e301bau),
    Golden("an apk whose classes move between dex files", 301, 0x756f94c0u),
    Golden("an apk that gains a dex", 34530, 0x05354812u),
    Golden("an apk from a plain zip", 371706, 0x286a56d4u),
    Golden("an apk from bytes that are no archive", 361275, 0x80bce3d6u),
    Golden("an edited zip", 11451, 0x053afc53u),
    Golden("a zip with a preamble", 8203, 0x82574ae6u),
    Golden("a zip that renames an entry", 200, 0x423547abu),
    Golden("a zip holding a zip and a dex", 5977, 0x6ec6a8e6u),
    Golden("an empty zip that gains an entry", 145, 0x5192db53u),
    Golden("a zip from bytes that are no archive", 32466, 0xcf04b43cu),
    Golden("a zip from an apk", 40255, 0x712e0b10u),
    Golden("edited bytes", 15021, 0xc438b720u),
    Golden("edited text", 5827, 0xff0b5b9fu),
    Golden("bytes from nothing", 3777, 0x22eb42ddu),
    Golden("nothing from bytes", 28, 0x18eaf84au),
    Golden("nothing from nothing", 27, 0x9d699ff0u),
    Golden("unrelated bytes", 20037, 0x8c6c8c9fu),
    Golden("bytes that only start like a zip", 44, 0xdc23ed8eu),
    Golden("bytes from a zip", 3966, 0x1a85524au)
  ).associateBy { it.name }

  @Test
  fun eachPatchIsTheOneItsPatcherWrote() {
    assertEquals(golden.keys, StructurePairs.all.map { it.name }.toSet())
    val packed = mutableSetOf<String>()
    for (pair in StructurePairs.all) {
      val patch = Kiff.createPatch(pair.source, pair.target)
      // Packing the tree changed what a writer writes since, and only where it makes the patch
      // smaller. Matching the content lazily packs the same content tighter. Unpacked, every patch
      // is still the pinned one.
      if (Kiff.info(patch).treePacked) {
        packed += pair.name
        assertTrue(patch.size < withStoredTree(patch).size, "${pair.name} was packed for nothing")
      }
      val plain = unpacked(patch)
      val expected = golden.getValue(pair.name)
      assertEquals(expected.size, plain.size, "size of ${pair.name}")
      assertEquals(expected.crc32, Crc32.compute(plain), "CRC-32 of ${pair.name}")
      assertEquals(pair.expected, Kiff.info(patch).patcher, "patcher byte of ${pair.name}")
      assertContentEquals(pair.target, Kiff.applyPatch(pair.source, patch), pair.name)
    }
    assertEquals(
      setOf(
        "an apk that gains a dex",
        "an apk from a plain zip",
        "an apk from bytes that are no archive",
        "a zip from bytes that are no archive"
      ),
      packed,
      "pairs whose tree is packed"
    )
  }

  @Test
  fun aDexIsTakenApartOnlyWhereThatPays() {
    val source = dex(seed = 1, codeSize = 60_000)
    val target = dex(seed = 1, codeSize = 63_000)
    val detected = Kiff.createPatch(source, target)
    val bytes = Kiff.createPatch(source, target, KiffOptions(containers = ContainerRegistry.Empty))

    assertTrue(detected.size <= bytes.size, "${detected.size} against ${bytes.size} as bytes")
    // Kiff 0.1.0 had no code for a dex, and wrote binary for it.
    assertEquals(PatcherId.BINARY, Kiff.info(detected).patcher)
    assertContentEquals(target, Kiff.applyPatch(source, detected))
  }

  @Test
  fun aFormatAddedToTheRegistryIsFoundInTheWholeFile() {
    val options = KiffOptions(containers = NestedContainers + TestSectionFormat())
    val source = sections(seed = 10, edited = false)
    val target = sections(seed = 10, edited = true)

    val structure =
      Structure.detect(com.linroid.kiff.io.ByteArraySource(target), options.containers)
    assertTrue(structure is Structure.Weighed && structure.format is TestSectionFormat)

    val report = KiffReports.explain(source, target, options)
    assertEquals(
      listOf("(section table)", "section 0", "section 1", "section 2"),
      report.regions.map { it.name }
    )
    val patch = Kiff.createPatch(source, target, options)
    assertEquals(report.patchSize, patch.size.toLong())
    assertTrue(patch.size < Kiff.createPatch(source, target).size, "taken apart, it is smaller")
    assertEquals(PatcherId.BINARY, Kiff.info(patch).patcher)
    assertContentEquals(target, Kiff.applyPatch(source, patch))
  }

  @Test
  fun anEmptyRegistryDescribesEveryFileAsBytes() {
    val options = KiffOptions(containers = ContainerRegistry.Empty)
    for (pair in StructurePairs.all) {
      val report = KiffReports.explain(pair.source, pair.target, options)
      assertEquals(listOf(RegionKind.WHOLE), report.regions.map { it.kind }, pair.name)
      val patch = Kiff.createPatch(pair.source, pair.target, options)
      assertEquals(PatcherId.BINARY, Kiff.info(patch).patcher, pair.name)
      assertContentEquals(pair.target, Kiff.applyPatch(pair.source, patch), pair.name)
    }
  }

  @Test
  fun explainMeasuresThePatchCreateBuilds() {
    for (pair in StructurePairs.all) {
      val report = KiffReports.explain(pair.source, pair.target)
      assertEquals(
        Kiff.createPatch(pair.source, pair.target).size.toLong(),
        report.patchSize,
        pair.name
      )
    }
  }

  @Test
  fun anApkIsAZipThatCarriesAManifest() {
    val apk = StructurePairs.all.first { it.name == "a rebuilt apk" }.target
    val zip = StructurePairs.all.first { it.name == "an apk from a plain zip" }.source
    assertTrue(KiffReports.isApk(apk))
    assertTrue(!KiffReports.isApk(zip))
    assertTrue(!KiffReports.isApk(ByteArray(100)))
  }

  private fun dex(seed: Int, codeSize: Int): ByteArray = TestDexBuilder()
    .section(0x0001, structuredBytes(4_000, seed))
    .section(0x0002, structuredBytes(2_000, seed + 1))
    .section(0x2001, structuredBytes(codeSize, seed + 2))
    .build()

  /**
   * Bytes, then lines of text, then bytes, with every seventh line changed when [edited]: what a
   * byte search over the whole file describes worse than a line diff of the middle section does.
   */
  private fun sections(seed: Int, edited: Boolean): ByteArray {
    val random = Random(seed)
    val lines = buildString {
      repeat(3_000) {
        append("key").append(it).append('=').append(random.nextInt(1_000))
        if (edited && it % 7 == 0) append(" changed")
        append('\n')
      }
    }
    return TestSectionFormat.build(
      listOf(
        structuredBytes(20_000, seed),
        lines.encodeToByteArray(),
        structuredBytes(20_000, seed + 1)
      )
    )
  }
}
