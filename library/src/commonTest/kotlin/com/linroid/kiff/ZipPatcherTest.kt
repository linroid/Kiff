package com.linroid.kiff

import com.linroid.kiff.zip.TestZipBuilder
import com.linroid.kiff.zip.ZipEntryStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ZipPatcherTest {

  private val patcher = ZipPatcher()

  private fun archive(block: TestZipBuilder.() -> Unit): ByteArray =
    TestZipBuilder().apply(block).build()

  @Test
  fun restoresAnIdenticalArchive() {
    val zip = archive {
      entry("a.txt", "hello")
      entry("dir/b.bin", structuredBytes(50_000, seed = 1))
    }
    val size = assertRestores(patcher, zip, zip)
    assertTrue(size < 64, "identical archives should cost almost nothing, got $size bytes")
  }

  @Test
  fun restoresEmptyArchive() {
    val empty = archive { }
    assertRestores(patcher, empty, empty)
    assertRestores(patcher, empty, archive { entry("new.txt", "added") })
    assertRestores(patcher, archive { entry("old.txt", "gone") }, empty)
  }

  @Test
  fun onlyTheChangedEntryCostsAnything() {
    val untouched = structuredBytes(200_000, seed = 2)
    val source = archive {
      entry("big.bin", untouched)
      entry("small.txt", "version 1")
    }
    val target = archive {
      entry("big.bin", untouched)
      entry("small.txt", "version 2")
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 512, "expected a patch sized like the change, got $size bytes")
  }

  @Test
  fun editsInsideAStoredEntryCostAboutTheEdit() {
    val original = structuredBytes(300_000, seed = 3)
    val edited = original.copyOfRange(0, 100_000) +
      Random(4).nextBytes(2_000) +
      original.copyOfRange(100_000, original.size)
    val size = assertRestores(
      patcher,
      archive { entry("payload.bin", original) },
      archive { entry("payload.bin", edited) }
    )
    assertTrue(size < 4_096, "expected a patch near the edit size, got $size bytes")
  }

  @Test
  fun aChangedEntryStaysAlignedWhenTheHeaderLengthChanges() {
    // Alignment padding lives in the local header's extra field, so the two headers differ in
    // length. The data comparison has to start at the data, not at the record.
    val original = structuredBytes(200_000, seed = 40)
    val edited = original.copyOf()
    for (at in 0 until edited.size step 40) {
      edited[at] = (edited[at] + 5).toByte()
    }
    val size = assertRestores(
      patcher,
      archive { entry("payload.bin", original) },
      archive { entry("payload.bin", edited, extra = ByteArray(7) { 3 }) }
    )
    assertTrue(size < edited.size / 8, "expected a difference-encoded patch, got $size bytes")
  }

  @Test
  fun restoresAddedAndRemovedEntries() {
    val shared = structuredBytes(40_000, seed = 5)
    val source = archive {
      entry("keep.bin", shared)
      entry("drop.txt", "removed in the new version")
    }
    val target = archive {
      entry("keep.bin", shared)
      entry("brand-new.txt", "added in the new version")
      entry("another.bin", structuredBytes(3_000, seed = 6))
    }
    assertRestores(patcher, source, target)
  }

  @Test
  fun aRenamedEntryIsPairedByContent() {
    val payload = structuredBytes(120_000, seed = 7)
    val source = archive { entry("classes2.dex", payload) }
    val target = archive { entry("classes3.dex", payload) }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 256, "a rename should be a copy, got $size bytes")
  }

  @Test
  fun reorderedEntriesAreStillCopies() {
    val first = structuredBytes(60_000, seed = 8)
    val second = structuredBytes(60_000, seed = 9)
    val source = archive {
      entry("first.bin", first)
      entry("second.bin", second)
    }
    val target = archive {
      entry("second.bin", second)
      entry("first.bin", first)
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 256, "reordering should be pure copies, got $size bytes")
  }

  @Test
  fun aMetadataOnlyChangeIsNearlyFree() {
    val payload = structuredBytes(150_000, seed = 10)
    val source = archive { entry("data.bin", payload, time = 0x1111) }
    val target = archive {
      entry("data.bin", payload, extra = ByteArray(12) { 7 }, time = 0x7777)
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 256, "a timestamp change should stay tiny, got $size bytes")
  }

  @Test
  fun restoresArchivesWithPreambleAndTrailingBlock() {
    val signingBlock = structuredBytes(20_000, seed = 11)
    val source = archive {
      preamble(structuredBytes(1_000, seed = 12))
      entry("a.bin", structuredBytes(30_000, seed = 13))
      beforeDirectory(signingBlock)
      comment("source archive")
    }
    val target = archive {
      preamble(structuredBytes(1_000, seed = 12))
      entry("a.bin", structuredBytes(30_000, seed = 13))
      entry("b.bin", structuredBytes(500, seed = 14))
      beforeDirectory(signingBlock)
      comment("target archive")
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 2_048, "the shared signing block should be copied, got $size bytes")
  }

  @Test
  fun restoresEntriesWithDataDescriptors() {
    val source = archive {
      entry("streamed.bin", structuredBytes(20_000, seed = 15), dataDescriptor = true)
    }
    val target = archive {
      entry("streamed.bin", structuredBytes(21_000, seed = 16), dataDescriptor = true)
      entry("plain.txt", "no descriptor here")
    }
    assertRestores(patcher, source, target)
  }

  @Test
  fun restoresDeflatedEntriesWithoutDecompressingThem() {
    // Method 8 with opaque payloads: Kiff never inflates, so it must not care what the bytes are.
    val source = archive { entry("a.bin", structuredBytes(40_000, seed = 17), method = 8) }
    val target = archive { entry("a.bin", structuredBytes(41_000, seed = 18), method = 8) }
    assertRestores(patcher, source, target)
  }

  @Test
  fun fallsBackToAByteScanForNonArchives() {
    val source = structuredBytes(30_000, seed = 19)
    val target = source.copyOfRange(0, 10_000) + structuredBytes(500, seed = 20) +
      source.copyOfRange(10_000, source.size)
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 2_048, "the fallback should still delta, got $size bytes")
  }

  @Test
  fun fallsBackWhenOnlyOneSideIsAnArchive() {
    val zip = archive { entry("a.txt", "hello") }
    assertRestores(patcher, structuredBytes(5_000, seed = 21), zip)
    assertRestores(patcher, zip, structuredBytes(5_000, seed = 22))
  }

  @Test
  fun analyzeReportsEveryEntryStatus() {
    val shared = structuredBytes(5_000, seed = 23)
    val stable = structuredBytes(1_000, seed = 24)
    val source = archive {
      entry("unchanged.bin", shared)
      entry("touched.bin", stable, time = 0x1111)
      entry("modified.txt", "before")
      entry("removed.txt", "gone")
    }
    val target = archive {
      entry("unchanged.bin", shared)
      entry("touched.bin", stable, time = 0x2222)
      entry("modified.txt", "after")
      entry("added.txt", "new")
    }

    val report = patcher.analyze(source, target)
    assertEquals(1, report.count(ZipEntryStatus.UNCHANGED))
    assertEquals(1, report.count(ZipEntryStatus.METADATA_CHANGED))
    assertEquals(1, report.count(ZipEntryStatus.MODIFIED))
    assertEquals(1, report.count(ZipEntryStatus.ADDED))
    assertEquals(1, report.count(ZipEntryStatus.REMOVED))
    assertEquals(4, report.changed.size)
    assertEquals(source.size, report.sourceSize)
    assertEquals(target.size, report.targetSize)
    assertTrue(report.changes.single { it.name == "unchanged.bin" }.stored)
  }

  @Test
  fun analyzeRejectsNonArchives() {
    val zip = archive { entry("a.txt", "hello") }
    assertFailsWith<KiffException.UnsupportedInput> {
      patcher.analyze(structuredBytes(100, seed = 25), zip)
    }
    assertFailsWith<KiffException.UnsupportedInput> {
      patcher.analyze(zip, structuredBytes(100, seed = 26))
    }
  }

  @Test
  fun patchesAreNotInterchangeableBetweenPatchers() {
    val source = archive { entry("a.txt", "one") }
    val target = archive { entry("a.txt", "two") }
    val zipPatch = patcher.createPatch(source, target)
    assertFailsWith<KiffException.InvalidPatch> { BinaryPatcher().applyPatch(source, zipPatch) }

    val binaryPatch = BinaryPatcher().createPatch(source, target)
    assertFailsWith<KiffException.InvalidPatch> { patcher.applyPatch(source, binaryPatch) }
  }

  @Test
  fun rejectsTheWrongSourceArchive() {
    val source = archive { entry("a.bin", structuredBytes(2_000, seed = 27)) }
    val target = archive { entry("a.bin", structuredBytes(2_100, seed = 28)) }
    val patch = patcher.createPatch(source, target)
    val other = archive { entry("a.bin", structuredBytes(2_000, seed = 29)) }
    assertFailsWith<KiffException.SourceMismatch> { patcher.applyPatch(other, patch) }
  }
}
