package com.linroid.kiff

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryDiffTest {

  private val algorithm = BinaryDiff()

  @Test
  fun restoresEmptyInputs() {
    assertRestores(algorithm, ByteArray(0), ByteArray(0))
    assertRestores(algorithm, ByteArray(0), "new content".encodeToByteArray())
    assertRestores(algorithm, "old content".encodeToByteArray(), ByteArray(0))
  }

  @Test
  fun restoresTinyInputs() {
    assertRestores(algorithm, byteArrayOf(1), byteArrayOf(1))
    assertRestores(algorithm, byteArrayOf(1), byteArrayOf(2))
    assertRestores(algorithm, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4))
  }

  @Test
  fun identicalFilesProduceATinyPatch() {
    val data = structuredBytes(512 * 1024, seed = 1)
    val size = assertRestores(algorithm, data, data)
    assertTrue(size < 64, "identical files should cost almost nothing, got $size bytes")
  }

  @Test
  fun insertionInTheMiddleCostsOnlyTheInsertion() {
    val source = structuredBytes(512 * 1024, seed = 2)
    val inserted = Random(99).nextBytes(1024)
    val target =
      source.copyOfRange(0, 200_000) + inserted + source.copyOfRange(200_000, source.size)
    val size = assertRestores(algorithm, source, target)
    assertTrue(size < 4096, "expected a patch near the insertion size, got $size bytes")
  }

  @Test
  fun deletionCostsAlmostNothing() {
    val source = structuredBytes(512 * 1024, seed = 3)
    val target = source.copyOfRange(0, 100_000) + source.copyOfRange(150_000, source.size)
    val size = assertRestores(algorithm, source, target)
    assertTrue(size < 1024, "expected a tiny patch for a deletion, got $size bytes")
  }

  @Test
  fun appendingCostsOnlyTheAppendedBytes() {
    val source = structuredBytes(256 * 1024, seed = 4)
    val target = source + structuredBytes(8 * 1024, seed = 5)
    val size = assertRestores(algorithm, source, target)
    assertTrue(size < 8 * 1024, "expected the patch to stay under the appended size, got $size")
  }

  @Test
  fun restoresWhenEveryByteShifts() {
    val source = structuredBytes(128 * 1024, seed = 6)
    val target = ByteArray(source.size) { (source[it] + 1).toByte() }
    assertRestores(algorithm, source, target)
  }

  @Test
  fun restoresUnrelatedFiles() {
    val source = Random(21).nextBytes(64 * 1024)
    val target = Random(22).nextBytes(64 * 1024)
    assertRestores(algorithm, source, target)
  }

  @Test
  fun restoresLongRuns() {
    val source = ByteArray(32 * 1024)
    val target = ByteArray(64 * 1024) { if (it < 40_000) 0 else 0x7F }
    val size = assertRestores(algorithm, source, target)
    assertTrue(size < 128, "runs should encode in a few bytes, got $size")
  }

  @Test
  fun restoresReorderedBlocks() {
    val first = structuredBytes(64 * 1024, seed = 7)
    val second = structuredBytes(64 * 1024, seed = 8)
    val size = assertRestores(algorithm, first + second, second + first)
    assertTrue(size < 1024, "reordering should be pure copies, got $size bytes")
  }

  @Test
  fun restoresManyScatteredEdits() {
    val source = structuredBytes(256 * 1024, seed = 9)
    val target = source.copyOf()
    val random = Random(10)
    repeat(200) {
      val at = random.nextInt(target.size)
      target[at] = (target[at] + 13).toByte()
    }
    assertRestores(algorithm, source, target)
  }

  @Test
  fun rejectsTheWrongSource() {
    val source = structuredBytes(4096, seed = 11)
    val target = structuredBytes(4096, seed = 12)
    val patch = algorithm.createPatch(source, target)

    val truncated = source.copyOfRange(0, 4000)
    assertFailsWith<KiffException.SourceMismatch> { algorithm.applyPatch(truncated, patch) }

    val corrupted = source.copyOf().also { it[0] = (it[0] + 1).toByte() }
    assertFailsWith<KiffException.SourceMismatch> { algorithm.applyPatch(corrupted, patch) }
  }

  @Test
  fun rejectsCorruptPatches() {
    val source = structuredBytes(4096, seed = 13)
    val target = structuredBytes(4096, seed = 14)
    val patch = algorithm.createPatch(source, target)

    assertFailsWith<KiffException.InvalidPatch> {
      algorithm.applyPatch(source, "not a patch at all".encodeToByteArray())
    }
    assertFailsWith<KiffException.InvalidPatch> {
      algorithm.applyPatch(source, patch.copyOfRange(0, patch.size / 2))
    }
    assertFailsWith<KiffException.InvalidPatch> {
      algorithm.applyPatch(source, patch.copyOf().also { it[5] = 9 })
    }
  }

  @Test
  fun reportsPatchMetadata() {
    val source = structuredBytes(8192, seed = 15)
    val target = structuredBytes(9000, seed = 16)
    val info = Kiff.info(algorithm.createPatch(source, target))
    assertTrue(info.ratio > 0.0)
    assertTrue(info.formatVersion == PatchFormat.VERSION)
  }
}
