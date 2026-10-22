package com.linroid.kiff

import com.linroid.kiff.container.ContainerRegistry
import com.linroid.kiff.container.TestDexBuilder
import com.linroid.kiff.container.TestSectionFormat
import com.linroid.kiff.format.Crc32
import com.linroid.kiff.format.withStoredTree
import com.linroid.kiff.region.RegionKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureDetectionTest {

  /** A patch Kiff 0.1.0's patchers wrote, by size and CRC-32 rather than whole. */
  private class Golden(val name: String, val size: Int, val crc32: UInt)

  /**
   * What the patcher each pair names wrote for it before detection existed, captured from that
   * code. Equal here means byte for byte the same patch: same format, same size.
   */
  private val golden = listOf(
    Golden("a rebuilt apk", 9792, 0x7cbd943bu),
    Golden("an apk whose classes move between dex files", 370, 0xb9217bfau),
    Golden("an apk that gains a dex", 24648, 0x87d08c08u),
    Golden("an apk from a plain zip", 343063, 0x7c47b655u),
    Golden("an apk from bytes that are no archive", 343645, 0xce2e3571u),
    Golden("an edited zip", 8252, 0x6bdc8f72u),
    Golden("a zip with a preamble", 1344, 0x51b9dad9u),
    Golden("a zip that renames an entry", 200, 0x423547abu),
    Golden("a zip holding a zip and a dex", 2785, 0x1ca84eeau),
    Golden("an empty zip that gains an entry", 145, 0x5192db53u),
    Golden("a zip from bytes that are no archive", 30973, 0xfa27640du),
    Golden("a zip from an apk", 30561, 0x32823d57u),
    Golden("edited bytes", 1280, 0xbe69b261u),
    Golden("edited text", 1012, 0x914dd18du),
    Golden("bytes from nothing", 3620, 0x29b924d1u),
    Golden("nothing from bytes", 28, 0x18eaf84au),
    Golden("nothing from nothing", 27, 0x9d699ff0u),
    Golden("unrelated bytes", 20037, 0x8c6c8c9fu),
    Golden("bytes that only start like a zip", 44, 0xdc23ed8eu),
    Golden("bytes from a zip", 1012, 0xd6f6f2e4u)
  ).associateBy { it.name }

  @Test
  fun eachPatchIsTheOneItsPatcherWrote() {
    assertEquals(golden.keys, StructurePairs.all.map { it.name }.toSet())
    val packed = mutableSetOf<String>()
    for (pair in StructurePairs.all) {
      val patch = Kiff.createPatch(pair.source, pair.target)
      // Packing the tree is the one thing a writer has changed since, and only where it makes the
      // patch smaller. Every other patch is still exactly the one written then, byte for byte, and
      // a packed one is that patch once its tree is stored again.
      val stored = if (Kiff.info(patch).treePacked) withStoredTree(patch) else patch
      if (stored !== patch) {
        packed += pair.name
        assertTrue(patch.size < stored.size, "${pair.name} was packed for nothing")
      }
      val expected = golden.getValue(pair.name)
      assertEquals(expected.size, stored.size, "size of ${pair.name}")
      assertEquals(expected.crc32, Crc32.compute(stored), "CRC-32 of ${pair.name}")
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
