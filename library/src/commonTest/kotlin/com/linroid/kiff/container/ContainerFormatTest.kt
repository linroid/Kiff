package com.linroid.kiff.container

import com.linroid.kiff.Kiff
import com.linroid.kiff.NestedContainers
import com.linroid.kiff.ZipPatcher
import com.linroid.kiff.apk.TestApkBuilder
import com.linroid.kiff.assertRestores
import com.linroid.kiff.region.RegionKind
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainerFormatTest {

  // ---- the rule every format has to obey -------------------------------------------------

  @Test
  fun zipTilesEveryArchiveShapeItClaims() {
    val shapes = mapOf(
      "plain" to TestZipBuilder().entry("a.txt", "alpha").entry("b.bin", structuredBytes(500, 1))
        .build(),
      "with a preamble" to TestZipBuilder().preamble(structuredBytes(300, 2))
        .entry("a.txt", "alpha").build(),
      "with a gap before the directory" to TestZipBuilder().entry("a.txt", "alpha")
        .beforeDirectory(structuredBytes(64, 3)).build(),
      "with a comment" to TestZipBuilder().entry("a.txt", "alpha").comment("trailing").build(),
      "with a data descriptor" to TestZipBuilder()
        .entry("a.txt", "alpha".encodeToByteArray(), dataDescriptor = true).build(),
      "empty" to TestZipBuilder().build()
    )
    for ((shape, bytes) in shapes) {
      assertNull(ContainerFormatTiling.describe(ZipFormat(), bytes), "zip, $shape")
    }
  }

  @Test
  fun apkTilesIncludingItsSigningBlock() {
    val apk = TestApkBuilder()
      .manifest()
      .resourceTable()
      .dex("classes.dex", structuredBytes(4_000, seed = 10))
      .signatureFiles(seed = 11)
      .signingBlock(structuredBytes(800, seed = 12))
      .build()
    assertNull(ContainerFormatTiling.describe(ApkFormat(), apk))
  }

  @Test
  fun theToyFormatTilesToo() {
    val file = TestSectionFormat.build(
      listOf(structuredBytes(300, 20), structuredBytes(150, 21)),
      trailer = structuredBytes(17, 22)
    )
    assertNull(ContainerFormatTiling.describe(TestSectionFormat(), file))
  }

  @Test
  fun aFormatThatLeavesAHoleIsReported() {
    // The mistake an implementer makes first: naming the parts the format documents and forgetting
    // the bytes between them.
    val leaky = object : ContainerFormat {
      override val name = "leaky"
      override fun detect(bytes: ByteArray, from: Int, to: Int) = true
      override fun decompose(bytes: ByteArray, from: Int, to: Int) = listOf(
        Child("head", RegionKind.CONTENT, from, from + 10),
        Child("tail", RegionKind.CONTENT, from + 20, to)
      )
    }
    val complaint = ContainerFormatTiling.describe(leaky, ByteArray(100))
    assertTrue(complaint.orEmpty().contains("gap of 10 bytes"), complaint.orEmpty())
  }

  // ---- what the seam is for ----------------------------------------------------------------

  private fun sectioned(changedSection: Boolean) = TestSectionFormat.build(
    listOf(
      structuredBytes(30_000, seed = 40),
      structuredBytes(30_000, seed = if (changedSection) 99 else 41),
      structuredBytes(30_000, seed = 42)
    )
  )

  @Test
  fun aFormatKiffHasNeverHeardOfIsOneClassAndARegistryEntry() {
    val source = TestZipBuilder().entry("payload.sect", sectioned(false)).build()
    val target = TestZipBuilder().entry("payload.sect", sectioned(true)).build()

    val unaware = ZipPatcher()
    val aware = ZipPatcher(containers = NestedContainers + TestSectionFormat())

    // Both restore: teaching a patcher a format changes how small a patch is, never whether it
    // applies. The decoder is never told which formats were used, so it cannot care.
    assertRestores(unaware, source, target)
    assertRestores(aware, source, target)

    // And a patch built by the aware encoder applies with a patcher that has no idea the format
    // exists, because the tree it produced is the same four node kinds.
    val patch = aware.createPatch(source, target)
    assertContentRestores(unaware, source, target, patch)
  }

  @Test
  fun decomposingANestedArchiveNeverCostsSize() {
    // Worth being honest about what nesting buys today: on stored content, nothing. A byte search
    // that can copy from anywhere in the source already handles reordered and edited entries, so
    // taking the archive apart mostly adds structure. What matters is that it cannot lose - the
    // decomposed form is weighed against describing the region whole and dropped if it is bigger.
    // Where it will pay is compressed children, which cannot be compared without being decoded.
    val a = structuredBytes(20_000, seed = 60)
    val b = structuredBytes(20_000, seed = 61)
    val c = structuredBytes(20_000, seed = 62)
    val inner = TestZipBuilder().entry("a.bin", a).entry("b.bin", b).entry("c.bin", c).build()
    val innerReordered = TestZipBuilder().entry("c.bin", c).entry("a.bin", a).entry("b.bin", b)
      .build()

    val source = TestZipBuilder().entry("bundle.zip", inner).build()
    val target = TestZipBuilder().entry("bundle.zip", innerReordered).build()

    val nested = ZipPatcher()
    val flat = ZipPatcher(containers = ContainerRegistry.Empty)
    assertRestores(nested, source, target)
    assertRestores(flat, source, target)

    val nestedSize = nested.createPatch(source, target).size
    val flatSize = flat.createPatch(source, target).size
    assertTrue(nestedSize <= flatSize, "nested $nestedSize should not lose to flat $flatSize")
  }

  @Test
  fun offeringANestedFormatNeverCostsSizeEither() {
    val original = (1..500).joinToString("\n") { "config entry $it = ${it * 7 % 31}" } + "\n"
    val edited = original.replace("config entry 250 ", "inserted\nconfig entry 250 ")
    val innerA = TestZipBuilder().entry("app.config", original)
      .entry("d.bin", structuredBytes(5_000, seed = 75)).build()
    val innerB = TestZipBuilder().entry("app.config", edited)
      .entry("d.bin", structuredBytes(5_000, seed = 75)).build()
    val source = TestZipBuilder().entry("bundle.zip", innerA).build()
    val target = TestZipBuilder().entry("bundle.zip", innerB).build()

    val nestedSize = ZipPatcher().createPatch(source, target).size
    val flatSize = ZipPatcher(containers = ContainerRegistry.Empty)
      .createPatch(source, target).size
    assertTrue(
      nestedSize <= flatSize,
      "knowing about the inner archive cost size: $nestedSize against $flatSize"
    )
  }

  @Test
  fun aNestedArchiveIsStillRestoredExactly() {
    val inner = TestZipBuilder()
      .entry("notes.txt", "alpha\nbeta\ngamma\n")
      .entry("data.bin", structuredBytes(5_000, seed = 70))
      .build()
    val innerEdited = TestZipBuilder()
      .entry("notes.txt", "alpha\nbeta changed\ngamma\n")
      .entry("data.bin", structuredBytes(5_000, seed = 70))
      .build()

    val source = TestZipBuilder().entry("bundle.zip", inner).build()
    val target = TestZipBuilder().entry("bundle.zip", innerEdited).build()
    assertRestores(Kiff.zip, source, target)
  }

  @Test
  fun aCompressedChildIsLeftOpaqueRatherThanGuessedAt() {
    // Deflated payloads cannot be recursed into until a patch can re-encode them byte for byte.
    // The archive still restores; it is simply described as the compressed bytes it holds.
    val inner = TestZipBuilder().entry("a.bin", structuredBytes(3_000, seed = 80)).build()
    val source = TestZipBuilder().entry("bundle.zip", inner, method = 8).build()
    val target = TestZipBuilder().entry("bundle.zip", inner, method = 8).build()
    assertRestores(Kiff.zip, source, target)
  }

  @Test
  fun registriesComposeWithoutTouchingTheFormat() {
    val registry = ContainerRegistry.Empty + ZipFormat() + TestSectionFormat()
    val zip = TestZipBuilder().entry("a.txt", "alpha").build()
    val sect = TestSectionFormat.build(listOf(structuredBytes(100, 90)))
    assertEquals("zip", registry.formatFor(zip, 0, zip.size)?.name)
    assertEquals("sect", registry.formatFor(sect, 0, sect.size)?.name)
    assertNull(registry.formatFor(structuredBytes(100, 91), 0, 100))
  }
}

/** Applies [patch] with a different patcher than built it, and checks the bytes come back. */
private fun assertContentRestores(
  patcher: ZipPatcher,
  source: ByteArray,
  target: ByteArray,
  patch: ByteArray
) {
  val restored = patcher.applyPatch(source, patch)
  assertTrue(restored.contentEquals(target), "a patch must apply whatever built it")
}
