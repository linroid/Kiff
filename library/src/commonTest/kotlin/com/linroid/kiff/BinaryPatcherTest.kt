package com.linroid.kiff

import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.PatchFormat
import com.linroid.kiff.format.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BinaryPatcherTest {

  private val patcher = BinaryPatcher()

  @Test
  fun restoresEmptyInputs() {
    assertRestores(patcher, ByteArray(0), ByteArray(0))
    assertRestores(patcher, ByteArray(0), "new content".encodeToByteArray())
    assertRestores(patcher, "old content".encodeToByteArray(), ByteArray(0))
  }

  @Test
  fun restoresTinyInputs() {
    assertRestores(patcher, byteArrayOf(1), byteArrayOf(1))
    assertRestores(patcher, byteArrayOf(1), byteArrayOf(2))
    assertRestores(patcher, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3, 4))
  }

  @Test
  fun identicalFilesProduceATinyPatch() {
    val data = structuredBytes(512 * 1024, seed = 1)
    val size = assertRestores(patcher, data, data)
    assertTrue(size < 64, "identical files should cost almost nothing, got $size bytes")
  }

  @Test
  fun insertionInTheMiddleCostsOnlyTheInsertion() {
    val source = structuredBytes(512 * 1024, seed = 2)
    val inserted = Random(99).nextBytes(1024)
    val target =
      source.copyOfRange(0, 200_000) + inserted + source.copyOfRange(200_000, source.size)
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 4096, "expected a patch near the insertion size, got $size bytes")
  }

  @Test
  fun deletionCostsAlmostNothing() {
    val source = structuredBytes(512 * 1024, seed = 3)
    val target = source.copyOfRange(0, 100_000) + source.copyOfRange(150_000, source.size)
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 1024, "expected a tiny patch for a deletion, got $size bytes")
  }

  @Test
  fun appendingCostsOnlyTheAppendedBytes() {
    val source = structuredBytes(256 * 1024, seed = 4)
    val target = source + structuredBytes(8 * 1024, seed = 5)
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 8 * 1024, "expected the patch to stay under the appended size, got $size")
  }

  @Test
  fun restoresWhenEveryByteShifts() {
    val source = structuredBytes(128 * 1024, seed = 6)
    val target = ByteArray(source.size) { (source[it] + 1).toByte() }
    assertRestores(patcher, source, target)
  }

  @Test
  fun restoresUnrelatedFiles() {
    val source = Random(21).nextBytes(64 * 1024)
    val target = Random(22).nextBytes(64 * 1024)
    assertRestores(patcher, source, target)
  }

  @Test
  fun restoresLongRuns() {
    val source = ByteArray(32 * 1024)
    val target = ByteArray(64 * 1024) { if (it < 40_000) 0 else 0x7F }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 128, "runs should encode in a few bytes, got $size")
  }

  @Test
  fun restoresReorderedBlocks() {
    val first = structuredBytes(64 * 1024, seed = 7)
    val second = structuredBytes(64 * 1024, seed = 8)
    val size = assertRestores(patcher, first + second, second + first)
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
    val size = assertRestores(patcher, source, target)
    assertTrue(size < 8 * 1024, "scattered edits should stay cheap, got $size bytes")
  }

  @Test
  fun editsTooDenseToMatchAreStillEncodedAsDifferences() {
    // Every 24th byte differs, so exact matches are short and useless: the patch is only small if
    // the region is described as a byte-wise difference from the aligned source.
    val source = structuredBytes(256 * 1024, seed = 30)
    val target = source.copyOf()
    for (at in 0 until target.size step 24) {
      target[at] = (target[at] + 7).toByte()
    }
    val size = assertRestores(patcher, source, target)
    assertTrue(size < target.size / 8, "expected a difference-encoded patch, got $size bytes")
  }

  @Test
  fun editsAfterAnInsertionRealignAndStayCheap() {
    val source = structuredBytes(256 * 1024, seed = 31)
    val head = source.copyOfRange(0, 60_000)
    val tail = source.copyOfRange(60_000, source.size).copyOf()
    for (at in 0 until tail.size step 40) {
      tail[at] = (tail[at] + 3).toByte()
    }
    val target = head + Random(32).nextBytes(4_000) + tail
    val size = assertRestores(patcher, source, target)
    assertTrue(size < target.size / 8, "expected realignment after the insertion, got $size bytes")
  }

  @Test
  fun rejectsTheWrongSource() {
    val source = structuredBytes(4096, seed = 11)
    val target = structuredBytes(4096, seed = 12)
    val patch = patcher.createPatch(source, target)

    val truncated = source.copyOfRange(0, 4000)
    assertFailsWith<KiffException.SourceMismatch> { patcher.applyPatch(truncated, patch) }

    val corrupted = source.copyOf().also { it[0] = (it[0] + 1).toByte() }
    assertFailsWith<KiffException.SourceMismatch> { patcher.applyPatch(corrupted, patch) }
  }

  @Test
  fun rejectsARestoreThatFailsItsChecksum() {
    val source = structuredBytes(4096, seed = 17)
    val target = structuredBytes(4096, seed = 18)
    val patch = patcher.createPatch(source, target)

    // Rewrite the header with a target checksum no restore can produce, leaving the delta itself
    // alone: the bytes come back correct, so only the final check can reject them.
    val reader = ByteReader(patch)
    val header = PatchFormat.readHeader(reader)
    val expected = header.targetCrc32 xor 1u
    val out = ByteWriter(patch.size)
    PatchFormat.writeHeader(
      out,
      header.patcher,
      header.sourceSize,
      header.sourceCrc32,
      header.targetSize,
      expected,
      // Carried through: the flags say how the payload is laid out, so dropping them would make
      // this a test of misparsing rather than of the final check.
      header.flags
    )
    out.writeBytes(patch.copyOfRange(reader.offset, patch.size))

    val failure = assertFailsWith<KiffException.VerificationFailed> {
      patcher.applyPatch(source, out.toByteArray())
    }
    assertTrue(expected.toHex() in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun rejectsCorruptPatches() {
    val source = structuredBytes(4096, seed = 13)
    val target = structuredBytes(4096, seed = 14)
    val patch = patcher.createPatch(source, target)

    assertFailsWith<KiffException.InvalidPatch> {
      patcher.applyPatch(source, "not a patch at all".encodeToByteArray())
    }
    assertFailsWith<KiffException.InvalidPatch> {
      patcher.applyPatch(source, patch.copyOfRange(0, patch.size / 2))
    }
    assertFailsWith<KiffException.InvalidPatch> {
      patcher.applyPatch(source, patch.copyOf().also { it[5] = 9 })
    }
  }

  @Test
  fun reportsPatchMetadata() {
    val source = structuredBytes(8192, seed = 15)
    val target = structuredBytes(9000, seed = 16)
    val info = Kiff.info(patcher.createPatch(source, target))
    assertTrue(info.ratio > 0.0)
    assertTrue(info.formatVersion == PatchFormat.VERSION)
  }
}
