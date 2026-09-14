package com.linroid.kiff

import com.linroid.kiff.apk.TestApkBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkDiffTest {

  private val algorithm = ApkDiff()

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
    val size = assertRestores(algorithm, original, original)
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
    assertRestores(algorithm, source, target)
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

    val apkSize = assertRestores(algorithm, source, target)
    val zipSize = assertRestores(ZipDiff(), source, target)
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
    val size = assertRestores(algorithm, source, target)
    assertTrue(size < 2_048, "an unchanged signing block should be copied, got $size bytes")

    val report = algorithm.analyze(source, target)
    assertTrue(report.signed)
    assertEquals(block.size + 32, report.sourceSigningBlockSize)
    assertEquals(report.sourceSigningBlockSize, report.targetSigningBlockSize)
  }

  @Test
  fun unsignedArchivesReportNoSigningBlock() {
    val source = apk { manifest(); dex("classes.dex", structuredBytes(5_000, 30)) }
    val target = apk { manifest(); dex("classes.dex", structuredBytes(5_100, 31)) }
    val report = algorithm.analyze(source, target)
    assertFalse(report.signed)
    assertEquals(0, report.sourceSigningBlockSize)
  }

  @Test
  fun classifiesEntriesByKind() {
    assertEquals(ApkEntryKind.MANIFEST, algorithm.kindOf("AndroidManifest.xml"))
    assertEquals(ApkEntryKind.RESOURCE_TABLE, algorithm.kindOf("resources.arsc"))
    assertEquals(ApkEntryKind.DEX, algorithm.kindOf("classes.dex"))
    assertEquals(ApkEntryKind.DEX, algorithm.kindOf("classes12.dex"))
    assertEquals(ApkEntryKind.NATIVE_LIBRARY, algorithm.kindOf("lib/arm64-v8a/libapp.so"))
    assertEquals(ApkEntryKind.RESOURCE, algorithm.kindOf("res/aa.png"))
    assertEquals(ApkEntryKind.ASSET, algorithm.kindOf("assets/model.tflite"))
    assertEquals(ApkEntryKind.SIGNATURE, algorithm.kindOf("META-INF/CERT.RSA"))
    assertEquals(ApkEntryKind.SIGNATURE, algorithm.kindOf("META-INF/MANIFEST.MF"))
    assertEquals(ApkEntryKind.METADATA, algorithm.kindOf("META-INF/services/foo"))
    assertEquals(ApkEntryKind.OTHER, algorithm.kindOf("stamp-cert-sha256"))
    assertEquals(ApkEntryKind.OTHER, algorithm.kindOf("lib/arm64-v8a/notalib.txt"))
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

    val report = algorithm.analyze(source, target)
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
    assertTrue(algorithm.isApk(sampleApk(seed = 50)))
    assertFalse(algorithm.isApk(apk { resource("a.png", structuredBytes(100, 51)) }))
    assertFalse(algorithm.isApk(structuredBytes(1_000, seed = 52)))
  }

  @Test
  fun stillPatchesArchivesThatAreNotApks() {
    val source = apk { resource("a.png", structuredBytes(30_000, 60)) }
    val target = apk {
      resource("a.png", structuredBytes(30_000, 60))
      resource("b.png", structuredBytes(2_000, 61))
    }
    val size = assertRestores(algorithm, source, target)
    assertTrue(size < 4_096, "a plain zip should still diff structurally, got $size bytes")
  }

  @Test
  fun fallsBackForInputsThatAreNotArchives() {
    val source = structuredBytes(30_000, seed = 70)
    val target = source.copyOfRange(0, 10_000) + structuredBytes(300, 71) +
      source.copyOfRange(10_000, source.size)
    assertRestores(algorithm, source, target)
    assertFailsWith<KiffException.UnsupportedInput> { algorithm.analyze(source, target) }
  }

  @Test
  fun apkPatchesAreNotInterchangeableWithZipPatches() {
    val source = sampleApk(seed = 80)
    val target = sampleApk(seed = 81)
    val apkPatch = algorithm.createPatch(source, target)
    assertFailsWith<KiffException.InvalidPatch> { ZipDiff().applyPatch(source, apkPatch) }
    assertEquals(AlgorithmId.APK, Kiff.info(apkPatch).algorithm)
  }

  @Test
  fun rejectsTheWrongSourceApk() {
    val source = sampleApk(seed = 82)
    val target = sampleApk(seed = 83)
    val patch = algorithm.createPatch(source, target)
    assertFailsWith<KiffException.SourceMismatch> { algorithm.applyPatch(sampleApk(84), patch) }
  }
}
