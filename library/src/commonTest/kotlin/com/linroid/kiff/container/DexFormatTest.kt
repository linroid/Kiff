package com.linroid.kiff.container

import com.linroid.kiff.Kiff
import com.linroid.kiff.NestedContainers
import com.linroid.kiff.ZipPatcher
import com.linroid.kiff.apk.TestApkBuilder
import com.linroid.kiff.ApkPatcher
import com.linroid.kiff.assertRestores
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DexFormatTest {

  private val format = DexFormat()

  private fun dex(seed: Int, codeSize: Int = 4_000) = TestDexBuilder()
    .section(0x0001, structuredBytes(800, seed))       // string_ids
    .section(0x0002, structuredBytes(400, seed + 1))   // type_ids
    .section(0x0006, structuredBytes(600, seed + 2))   // class_defs
    .section(0x2001, structuredBytes(codeSize, seed + 3))  // code
    .section(0x2002, structuredBytes(1_200, seed + 4)) // string_data
    .build()

  @Test
  fun itTilesTheWholeFile() {
    assertNull(ContainerFormatTiling.describe(format, dex(seed = 1)))
  }

  @Test
  fun itTilesInsideAnArchiveToo() {
    // The offsets a dex records are relative to its own start, so a dex found inside something
    // else has to be read in its own coordinates and reported in its parent's.
    val bytes = dex(seed = 2)
    val wrapped = ByteArray(64) + bytes + ByteArray(32)
    assertNull(ContainerFormatTiling.describe(format, wrapped, 64, 64 + bytes.size))
  }

  @Test
  fun sectionsAreNamedAfterTheirMapType() {
    val children = format.decompose(dex(seed = 3), 0, dex(seed = 3).size)
    val names = children.map { it.name }
    assertEquals("header", names.first())
    assertEquals("map_list", names.last())
    assertTrue("code" in names, names.toString())
    assertTrue("string_data" in names, names.toString())
  }

  @Test
  fun theHeaderIsNotSwallowedByTheFirstSection() {
    val bytes = dex(seed = 4)
    val header = format.decompose(bytes, 0, bytes.size).first()
    assertEquals(0, header.from)
    assertEquals(112, header.to, "the header runs to the first section, not past it")
  }

  @Test
  fun anythingThatIsNotADexIsLeftAlone() {
    assertFalse(format.detect(structuredBytes(500, seed = 5), 0, 500))
    assertFalse(format.detect(ByteArray(8), 0, 8), "too short to hold a header")
    // "dex\n" followed by something that is not three digits and a NUL.
    val impostor = "dex\nXYZ!".encodeToByteArray() + ByteArray(200)
    assertFalse(format.detect(impostor, 0, impostor.size))
  }

  @Test
  fun aMapListThatIsNotSortedIsRefused() {
    val bytes = dex(seed = 6)
    // Corrupt the last map item's offset so the list no longer ascends.
    val mapOffset = u32(bytes, 0x34)
    val count = u32(bytes, mapOffset)
    val lastOffset = mapOffset + 4 + (count - 1) * 12 + 8
    bytes[lastOffset] = 0
    bytes[lastOffset + 1] = 0
    bytes[lastOffset + 2] = 0
    bytes[lastOffset + 3] = 0
    assertTrue(
      format.decompose(bytes, 0, bytes.size).isEmpty(),
      "a dex it cannot describe exactly must be left whole, not described wrongly"
    )
  }

  @Test
  fun aDexInsideAnApkStillRestoresExactly() {
    val source = TestZipBuilder().entry("classes.dex", dex(seed = 10)).build()
    val target = TestZipBuilder().entry("classes.dex", dex(seed = 11)).build()
    assertRestores(Kiff.zip, source, target)
  }

  @Test
  fun knowingAboutDexNeverCostsSize() {
    val source = TestZipBuilder().entry("classes.dex", dex(seed = 20)).build()
    val target = TestZipBuilder().entry("classes.dex", dex(seed = 20, codeSize = 4_400)).build()

    val aware = ZipPatcher(containers = NestedContainers).createPatch(source, target).size
    val unaware = ZipPatcher(containers = ContainerRegistry(ZipFormat()))
      .createPatch(source, target).size
    assertTrue(aware <= unaware, "describing the dex cost size: $aware against $unaware")
  }

  @Test
  fun takingADexApartNeverCostsSizeEvenWhenTheDexAreGrouped() {
    // Grouping lets a dex be searched against every dex, which makes describing it whole much
    // better and so raises the bar its sections have to clear. They clear it or they are dropped;
    // what must not happen is the two features costing size by working against each other.
    val a = structuredBytes(50_000, seed = 60)
    val b = structuredBytes(50_000, seed = 61)
    val source = TestApkBuilder()
      .manifest()
      .dex("classes.dex", dexWith(a, seed = 62))
      .dex("classes2.dex", dexWith(b, seed = 63))
      .build()
    val target = TestApkBuilder()
      .manifest()
      .dex("classes.dex", dexWith(a, seed = 64))
      .dex("classes2.dex", dexWith(b, seed = 65))
      .build()

    val withDex = ApkPatcher(containers = NestedContainers)
    val withoutDex = ApkPatcher(containers = ContainerRegistry(ZipFormat()))
    val aware = assertRestores(withDex, source, target)
    val unaware = assertRestores(withoutDex, source, target)
    assertTrue(aware <= unaware, "describing the dex cost size: $aware against $unaware")
  }

  private fun dexWith(code: ByteArray, seed: Int) = TestDexBuilder()
    .section(0x0001, structuredBytes(600, seed))
    .section(0x2001, code)
    .section(0x2002, structuredBytes(900, seed + 100))
    .build()

  private fun u32(bytes: ByteArray, at: Int): Int {
    var value = 0
    for (i in 3 downTo 0) value = (value shl 8) or (bytes[at + i].toInt() and 0xFF)
    return value
  }
}
