package com.linroid.kiff.format

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.PatcherId
import com.linroid.kiff.structuredBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PatchFormatTest {

  private val source = structuredBytes(8_000, seed = 71)
  private val target = structuredBytes(8_400, seed = 72)

  @Test
  fun patchesAreWrittenAsVersionOne() {
    assertEquals(1, Kiff.info(Kiff.binary.createPatch(source, target)).formatVersion)
  }

  @Test
  fun unknownVersionsAreRejected() {
    val patch = Kiff.binary.createPatch(source, target)
    val future = patch.copyOf().also { it[VERSION_BYTE] = 2 }
    val failure = assertFailsWith<KiffException.InvalidPatch> {
      Kiff.binary.applyPatch(source, future)
    }
    assertTrue("2" in failure.message.orEmpty(), failure.message.orEmpty())
  }

  @Test
  fun sizesAreReportedAsLong() {
    val info = Kiff.info(Kiff.binary.createPatch(source, target))
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
  }
}
