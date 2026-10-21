package com.linroid.kiff

import com.linroid.kiff.apk.ApkEntryKind
import com.linroid.kiff.apk.TestApkBuilder
import com.linroid.kiff.container.TestDexBuilder
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkPatcherTest {

  private val patcher = ApkPatcher()

  private fun apk(block: TestApkBuilder.() -> Unit): ByteArray =
    TestApkBuilder().apply(block).build()

  private fun sampleApk(seed: Int, extra: TestApkBuilder.() -> Unit = {}): ByteArray = apk {
    manifest()
    dex("classes.dex", structuredBytes(120_000, seed))
    dex("classes2.dex", structuredBytes(90_000, seed + 1))
    nativeLibrary("arm64-v8a/libapp.so", structuredBytes(200_000, seed + 2))
    resource("aa.png", structuredBytes(20_000, seed + 3))
    resourceTable()
    signatureFiles(seed + 4)
    signingBlock(structuredBytes(6_000, seed + 5))
    extra()
  }

  @Test
  fun restoresAnIdenticalApk() {
    val original = sampleApk(seed = 1)
    val size = assertRestores(patcher, original, original)
    assertTrue(size < 128, "identical packages should cost almost nothing, got $size bytes")
  }

  @Test
  fun restoresARebuiltApk() {
    val source = sampleApk(seed = 2)
    val target = apk {
      manifest()
      dex("classes.dex", structuredBytes(120_000, 2))
      dex("classes2.dex", structuredBytes(95_000, 3))
      nativeLibrary("arm64-v8a/libapp.so", structuredBytes(200_000, 4))
      resource("aa.png", structuredBytes(20_000, 5))
      resourceTable()
      signatureFiles(seed = 90)
      signingBlock(structuredBytes(6_000, 7))
    }
    assertRestores(patcher, source, target)
  }

  @Test
  fun classesThatMoveBetweenDexFilesAreStillFound() {
    // A rebuild repartitions classes across the dex files, so a class can change file while
    // changing not at all. Searching each dex only against the dex of the same name cannot find
    // those bytes even though they sit in the source; grouping the dex entries can.
    val a = structuredBytes(60_000, seed = 40)
    val b = structuredBytes(60_000, seed = 41)
    val c = structuredBytes(60_000, seed = 42)
    val source = apk {
      manifest()
      dex("classes.dex", a + b)
      dex("classes2.dex", c)
    }
    val target = apk {
      manifest()
      dex("classes.dex", a)
      dex("classes2.dex", b + c)
    }

    val grouped = assertRestores(Kiff.apk, source, target)
    // The zip patcher describes the same archive without knowing the dex files belong together.
    val ungrouped = assertRestores(Kiff.zip, source, target)
    assertTrue(
      grouped < ungrouped / 2,
      "grouping the dex entries should find the moved classes: $grouped against $ungrouped"
    )
  }

  @Test
  fun groupingStillFindsMatchesOnADexsOwnGrid() {
    // Rebuilt code differs from its counterpart a byte in every dozen, so it is described as
    // differences, and after each inserted method the search has to realign on an exact match.
    // Here that match is exactly as long as the shortest one the search can find, and starts on
    // the dex's four-byte grid - often all a rewritten method shares with its old self. The index
    // over every dex has to sample that grid just as the counterpart's own index does, wherever
    // the dex's local header happens to sit.
    val code = Random(97).nextBytes(60_000)
    val random = Random(98)
    val rebuilt = ByteWriter(70_000)
    var at = 0
    while (at < code.size) {
      val end = minOf(code.size, at + 3_000)
      // Left as it was, between two changed bytes so the match cannot grow past 16.
      val anchor = at + 256
      for (i in at until end) {
        val kept = i in anchor until anchor + 16
        val changed = !kept && (i % 12 == 0 || i == anchor - 1 || i == anchor + 16)
        rebuilt.writeByte(code[i] + if (changed) 1 else 0)
      }
      // Too long a shift to be found without a match.
      if (end < code.size) rebuilt.writeBytes(random.nextBytes(4 * random.nextInt(64, 128)))
      at = end
    }
    val sibling = Random(99).nextBytes(60_000)
    fun dexWith(code: ByteArray) = TestDexBuilder()
      .section(0x0001, structuredBytes(600, seed = 100)) // string_ids, so code starts at 712
      .section(0x2001, code)                              // code
      .build()
    val source = apk {
      manifest()
      dex("classes.dex", dexWith(code))
      dex("classes2.dex", dexWith(sibling))
    }
    val target = apk {
      manifest()
      dex("classes.dex", dexWith(rebuilt.toByteArray()))
      dex("classes2.dex", dexWith(sibling))
    }

    val grouped = assertRestores(Kiff.apk, source, target)
    val ungrouped = assertRestores(Kiff.zip, source, target)
    assertTrue(
      grouped <= ungrouped,
      "searching every dex should not miss what the counterpart's index finds: " +
        "$grouped against $ungrouped"
    )
  }

  @Test
  fun aRenumberedDexIsPairedWithItsNeighbour() {
    // A build that gains a dex renumbers the rest: classes3.dex has no counterpart by name and its
    // bytes are new, so only the ordinal says which source dex it belongs next to.
    val shared = structuredBytes(120_000, seed = 10)
    val second = structuredBytes(40_000, seed = 11)
    val third = second.copyOf().also { bytes ->
      for (at in 0 until bytes.size step 50) bytes[at] = (bytes[at] + 9).toByte()
    }
    val source = apk {
      manifest()
      dex("classes.dex", shared)
      dex("classes2.dex", second)
    }
    val target = apk {
      manifest()
      dex("classes.dex", shared)
      dex("classes2.dex", second)
      dex("classes3.dex", third)
    }

    val apkSize = assertRestores(patcher, source, target)
    val zipSize = assertRestores(ZipPatcher(), source, target)
    assertTrue(
      apkSize < zipSize / 2,
      "ordinal pairing should beat the generic zip pairing: apk=$apkSize zip=$zipSize"
    )
  }

  @Test
  fun reproducesTheSigningBlockExactly() {
    val block = structuredBytes(30_000, seed = 20)
    val source = apk {
      manifest()
      dex("classes.dex", structuredBytes(50_000, 21))
      signingBlock(block)
    }
    val target = apk {
      manifest()
      dex("classes.dex", structuredBytes(50_000, 21))
      resource("new.png", structuredBytes(1_000, 22))
      signingBlock(block)
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 2_048, "an unchanged signing block should be copied, got $size bytes")

    val report = patcher.analyze(source, target)
    assertTrue(report.signed)
    assertEquals(block.size + 32, report.sourceSigningBlockSize)
    assertEquals(report.sourceSigningBlockSize, report.targetSigningBlockSize)
  }

  @Test
  fun unsignedArchivesReportNoSigningBlock() {
    val source = apk { manifest(); dex("classes.dex", structuredBytes(5_000, 30)) }
    val target = apk { manifest(); dex("classes.dex", structuredBytes(5_100, 31)) }
    val report = patcher.analyze(source, target)
    assertFalse(report.signed)
    assertEquals(0, report.sourceSigningBlockSize)
  }

  @Test
  fun classifiesEntriesByKind() {
    assertEquals(ApkEntryKind.MANIFEST, patcher.kindOf("AndroidManifest.xml"))
    assertEquals(ApkEntryKind.RESOURCE_TABLE, patcher.kindOf("resources.arsc"))
    assertEquals(ApkEntryKind.DEX, patcher.kindOf("classes.dex"))
    assertEquals(ApkEntryKind.DEX, patcher.kindOf("classes12.dex"))
    assertEquals(ApkEntryKind.NATIVE_LIBRARY, patcher.kindOf("lib/arm64-v8a/libapp.so"))
    assertEquals(ApkEntryKind.RESOURCE, patcher.kindOf("res/aa.png"))
    assertEquals(ApkEntryKind.ASSET, patcher.kindOf("assets/model.tflite"))
    assertEquals(ApkEntryKind.SIGNATURE, patcher.kindOf("META-INF/CERT.RSA"))
    assertEquals(ApkEntryKind.SIGNATURE, patcher.kindOf("META-INF/MANIFEST.MF"))
    assertEquals(ApkEntryKind.METADATA, patcher.kindOf("META-INF/services/foo"))
    assertEquals(ApkEntryKind.OTHER, patcher.kindOf("stamp-cert-sha256"))
    assertEquals(ApkEntryKind.OTHER, patcher.kindOf("lib/arm64-v8a/notalib.txt"))
  }

  @Test
  fun groupsTheReportByKind() {
    val source = sampleApk(seed = 40)
    val target = apk {
      manifest()
      dex("classes.dex", structuredBytes(120_000, 40))
      dex("classes2.dex", structuredBytes(90_000, 41))
      nativeLibrary("arm64-v8a/libapp.so", structuredBytes(260_000, 99))
      nativeLibrary("arm64-v8a/libextra.so", structuredBytes(10_000, 98))
      resourceTable()
      signatureFiles(seed = 44)
      signingBlock(structuredBytes(6_000, 45))
    }

    val report = patcher.analyze(source, target)
    val native = report.kinds.single { it.kind == ApkEntryKind.NATIVE_LIBRARY }
    assertEquals(2, native.entryCount)
    assertEquals(1, native.changedCount)
    assertEquals(1, native.addedCount)
    assertTrue(native.growth > 0, "native libraries grew, got ${native.growth}")

    val resources = report.kinds.single { it.kind == ApkEntryKind.RESOURCE }
    assertEquals(1, resources.removedCount)
    assertEquals(0, resources.entryCount)

    val dex = report.kinds.single { it.kind == ApkEntryKind.DEX }
    assertEquals(2, dex.entryCount)
    assertEquals(0, dex.changedCount)
  }

  @Test
  fun recognisesWhatIsAnApk() {
    assertTrue(patcher.isApk(sampleApk(seed = 50)))
    assertFalse(patcher.isApk(apk { resource("a.png", structuredBytes(100, 51)) }))
    assertFalse(patcher.isApk(structuredBytes(1_000, seed = 52)))
  }

  @Test
  fun stillPatchesArchivesThatAreNotApks() {
    val source = apk { resource("a.png", structuredBytes(30_000, 60)) }
    val target = apk {
      resource("a.png", structuredBytes(30_000, 60))
      resource("b.png", structuredBytes(2_000, 61))
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 4_096, "a plain zip should still diff structurally, got $size bytes")
  }

  @Test
  fun fallsBackForInputsThatAreNotArchives() {
    val source = structuredBytes(30_000, seed = 70)
    val target = source.copyOfRange(0, 10_000) + structuredBytes(300, 71) +
      source.copyOfRange(10_000, source.size)
    assertRestores(patcher, source, target)
    assertFailsWith<KiffException.UnsupportedInput> { patcher.analyze(source, target) }
  }

  @Test
  fun anApkPatchSaysWhoBuiltItAndAppliesWithAnyPatcher() {
    val source = sampleApk(seed = 80)
    val target = sampleApk(seed = 81)
    val apkPatch = patcher.createPatch(source, target)
    assertEquals(PatcherId.APK, Kiff.info(apkPatch).patcher)
    assertContentEquals(target, ZipPatcher().applyPatch(source, apkPatch))
  }

  @Test
  fun rejectsTheWrongSourceApk() {
    val source = sampleApk(seed = 82)
    val target = sampleApk(seed = 83)
    val patch = patcher.createPatch(source, target)
    assertFailsWith<KiffException.SourceMismatch> { patcher.applyPatch(sampleApk(84), patch) }
  }
}
