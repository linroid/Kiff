package com.linroid.kiff.apk

import com.linroid.kiff.Kiff
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApkPatchReportTest {

  private fun apk(dexSeed: Int, librarySeed: Int) = TestApkBuilder()
    .manifest()
    .resourceTable()
    .dex("classes.dex", structuredBytes(40_000, seed = dexSeed))
    .dex("classes2.dex", structuredBytes(20_000, seed = 910))
    .nativeLibrary("arm64-v8a/libfoo.so", structuredBytes(30_000, seed = librarySeed))
    .resource("drawable/icon.png", structuredBytes(4_000, seed = 911))
    .signatureFiles(seed = 912)
    .signingBlock(structuredBytes(2_000, seed = 913))
    .build()

  private val source = apk(dexSeed = 920, librarySeed = 930)
  private val target = apk(dexSeed = 921, librarySeed = 930)

  @Test
  fun costIsGroupedByEntryKind() {
    val report = Kiff.apk.explainKinds(source, target)
    val kinds = report.kinds.associateBy { it.kind }

    val dex = kinds.getValue(ApkEntryKind.DEX)
    assertEquals(2, dex.entryCount)
    assertTrue(dex.literalBytes > 0, "the changed dex has to be carried")

    val library = kinds.getValue(ApkEntryKind.NATIVE_LIBRARY)
    assertEquals(0L, library.literalBytes, "an unchanged library is copied outright")
    assertTrue(library.instructionBytes > 0, "it still costs the instruction that points at it")
  }

  @Test
  fun theChangedKindDominatesTheReport() {
    // Only classes.dex differs between the two builds, so dex should lead the ranking - which is
    // the question the report exists to answer.
    val report = Kiff.apk.explainKinds(source, target)
    assertEquals(ApkEntryKind.DEX, report.kinds.first().kind)
  }

  @Test
  fun kindBytesAddUpToTheEntryRegions() {
    val report = Kiff.apk.explainKinds(source, target)
    val entryBytes = report.regions.bytes(RegionKind.CONTENT)
    assertEquals(entryBytes, report.kinds.sumOf { it.patchBytes })
  }

  @Test
  fun gapsAndDirectoryAreReportedSeparately() {
    val report = Kiff.apk.explainKinds(source, target)
    assertTrue(
      report.kinds.none { it.kind == ApkEntryKind.OTHER },
      "the signing block is a gap, not an entry of an unknown kind"
    )
    assertEquals(report.regions.bytes(RegionKind.GAP), report.gapBytes)
    assertEquals(report.regions.bytes(RegionKind.INDEX), report.directoryBytes)
  }

  @Test
  fun totalsMatchThePatchItDescribes() {
    val report = Kiff.apk.explainKinds(source, target)
    assertEquals(target.size.toLong(), report.targetSize)
    assertEquals(Kiff.apk.createPatch(source, target).size.toLong(), report.patchSize)
  }
}
