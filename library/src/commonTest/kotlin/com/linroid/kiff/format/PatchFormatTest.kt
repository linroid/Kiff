package com.linroid.kiff.format

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.PatcherId
import com.linroid.kiff.io.ByteArrayRestoreTarget
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatchFormatTest {

  private val source = structuredBytes(8_000, seed = 71)
  private val target = structuredBytes(8_400, seed = 72)

  @Test
  fun patchesAreWrittenAsVersionOne() {
    assertEquals(1, Kiff.info(Kiff.createPatch(source, target)).formatVersion)
  }

  @Test
  fun unknownVersionsAreRejected() {
    val patch = Kiff.createPatch(source, target)
    val future = patch.copyOf().also { it[VERSION_BYTE] = 2 }
    val failure = assertFailsWith<KiffException.InvalidPatch> {
      Kiff.applyPatch(source, future)
    }
    assertTrue("2" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun everyReservedFlagBitIsRefused() {
    // A flag changes how the payload reads, so one this build does not know has to stop it on every
    // path - including the header alone, which is all an updater looks at before downloading.
    val patch = Kiff.createPatch(source, target)
    for (bit in 2..7) {
      val flagged = patch.copyOf().also {
        it[FLAGS_BYTE] = (PatchFormat.FLAG_REGION_CHECKSUMS or (1 shl bit)).toByte()
      }
      assertFailsWith<KiffException.InvalidPatch>("bit $bit") { Kiff.info(flagged) }
      assertFailsWith<KiffException.InvalidPatch>("bit $bit") {
        Kiff.applyPatch(source, flagged)
      }
      assertFailsWith<KiffException.InvalidPatch>("bit $bit, streaming") {
        val out = ByteArrayRestoreTarget(target.size)
        Kiff.applyPatch(ByteArraySource(source), flagged, out)
      }
    }
  }

  @Test
  fun thePackedTreeFlagIsKnownButNotTakenOnTrust() {
    // The flag changes where the payload's fields are, so setting or clearing it on a patch that
    // was written the other way has to be refused when applied, not read as something else.
    val patch = Kiff.createPatch(source, target)
    assertFalse(Kiff.info(patch).treePacked)
    val flagged = patch.copyOf().also {
      it[FLAGS_BYTE] = (it[FLAGS_BYTE].toInt() or PatchFormat.FLAG_PACKED_TREE).toByte()
    }
    assertTrue(Kiff.info(flagged).treePacked)
    assertFailsWith<KiffException.InvalidPatch> { Kiff.applyPatch(source, flagged) }

    val packed = withPackedTree(patch)
    assertContentEquals(target, Kiff.applyPatch(source, packed))
    val unflagged = packed.copyOf().also {
      it[FLAGS_BYTE] = (it[FLAGS_BYTE].toInt() and PatchFormat.FLAG_PACKED_TREE.inv()).toByte()
    }
    assertFailsWith<KiffException.InvalidPatch> { Kiff.applyPatch(source, unflagged) }
    assertFailsWith<KiffException.InvalidPatch> {
      Kiff.applyPatch(ByteArraySource(source), unflagged, ByteArrayRestoreTarget(target.size))
    }
  }

  @Test
  fun readableFormatsNameWhatThisBuildWritesAndNothingItRefuses() {
    val written = Kiff.info(Kiff.createPatch(source, target)).formatVersion
    assertTrue(written in Kiff.readableFormats)
    // The version unknownVersionsAreRejected shows refused: an updater skipping by this set has to
    // skip exactly what applyPatch would turn down.
    assertFalse(2 in Kiff.readableFormats)
    // A packed tree is a flag within version 1, not a version of its own.
    assertEquals(setOf(1), Kiff.readableFormats)
  }

  @Test
  fun sizesAreReportedAsLong() {
    val info = Kiff.info(Kiff.createPatch(source, target))
    assertEquals(source.size.toLong(), info.sourceSize)
    assertEquals(target.size.toLong(), info.targetSize)
  }

  @Test
  fun theHeaderCanDescribeSizesBeyondTwoGigabytes() {
    // The inputs themselves cannot be built here, but the container has to survive the round trip
    // or nothing above 2 GB could ever be described.
    val out = ByteWriter(32)
    val huge = 9_000_000_000L
    PatchFormat.writeHeader(out, PatcherId.BINARY, huge, 1u, huge + 1, 2u)
    val header = PatchFormat.readHeader(com.linroid.kiff.format.ByteReader(out.toByteArray()))
    assertEquals(huge, header.sourceSize)
    assertEquals(huge + 1, header.targetSize)
  }

  private companion object {
    /** Right after the 4-byte "KIFF" magic. */
    const val VERSION_BYTE = 4

    /** After the version and the patcher. */
    const val FLAGS_BYTE = 6
  }
}
