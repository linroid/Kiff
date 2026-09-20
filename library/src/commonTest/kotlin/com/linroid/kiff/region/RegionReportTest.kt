package com.linroid.kiff.region

import com.linroid.kiff.Kiff
import com.linroid.kiff.structuredBytes
import com.linroid.kiff.zip.TestZipBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RegionReportTest {

  private val stable = structuredBytes(20_000, seed = 900)
  private val other = structuredBytes(6_000, seed = 901)

  private fun archive(changing: ByteArray) = TestZipBuilder()
    .entry("stable.bin", stable)
    .entry("changing.bin", changing)
    .entry("other.bin", other)
    .build()

  private val source = archive(structuredBytes(30_000, seed = 902))
  private val target = archive(structuredBytes(30_000, seed = 903))

  @Test
  fun everyTargetByteBelongsToExactlyOneRegion() {
    val report = Kiff.zip.explain(source, target)
    assertEquals(target.size.toLong(), report.regions.sumOf { it.targetBytes })
    assertEquals(target.size.toLong(), report.targetSize)
  }

  @Test
  fun measuringDoesNotChangeThePatch() {
    // The whole point of recording around the encode rather than inside it: an explained patch is
    // the patch createPatch would have produced, so the numbers describe the real thing.
    val measured = Kiff.zip.explain(source, target).patchSize
    assertEquals(Kiff.zip.createPatch(source, target).size.toLong(), measured)
  }

  @Test
  fun anUnchangedEntryCarriesNoContentButStillCostsAnInstruction() {
    val report = Kiff.zip.explain(source, target)
    val unchanged = report.regions.single { it.name == "stable.bin" }
    assertEquals(0L, unchanged.literalBytes, "an identical entry carries no content")
    assertTrue(unchanged.carriedNothing)
    assertTrue(unchanged in report.referenced)
    // Cheap is not free: pointing at 20 KB of source still costs a COPY tag plus its offset.
    assertTrue(
      unchanged.instructionBytes in 1..16,
      "expected a small COPY instruction, got ${unchanged.instructionBytes}"
    )
  }

  @Test
  fun theChangedEntryCarriesTheCost() {
    val report = Kiff.zip.explain(source, target)
    val mostExpensive = report.costly.first()
    assertEquals("changing.bin", mostExpensive.name)
    assertEquals(RegionKind.CONTENT, mostExpensive.kind)
    assertTrue(mostExpensive.literalBytes > 0, "replaced content has to be carried")
  }

  @Test
  fun instructionsAndLiteralsAddUpToTheAttributedTotal() {
    val report = Kiff.zip.explain(source, target)
    assertEquals(report.attributedBytes, report.instructionBytes + report.literalBytes)
    assertTrue(report.instructionBytes > 0, "describing anything costs instructions")
  }

  @Test
  fun theDirectoryIsReportedAsIndex() {
    val report = Kiff.zip.explain(source, target)
    val directory = report.regions.single { it.kind == RegionKind.INDEX }
    assertTrue(directory.targetBytes > 0, "the central directory covers bytes")
  }

  @Test
  fun aGapIsItsOwnRegion() {
    val withGap = TestZipBuilder()
      .entry("a.bin", stable)
      .beforeDirectory(structuredBytes(4_000, seed = 904))
      .build()
    val changed = TestZipBuilder()
      .entry("a.bin", stable)
      .beforeDirectory(structuredBytes(4_000, seed = 905))
      .build()
    val report = Kiff.zip.explain(withGap, changed)
    val gap = report.regions.single { it.kind == RegionKind.GAP }
    assertEquals(4_000L, gap.targetBytes)
    assertTrue(gap.literalBytes > 0, "a changed gap has to be carried")
  }

  @Test
  fun theBinaryPatcherReportsTheWholeFileAsOneRegion() {
    val report = Kiff.binary.explain(stable, other)
    val whole = assertNotNull(report.regions.singleOrNull())
    assertEquals(RegionKind.WHOLE, whole.kind)
    assertEquals(other.size.toLong(), whole.targetBytes)
  }

  @Test
  fun attributionCoversTheDeltaItReports() {
    val report = Kiff.zip.explain(source, target)
    // Literals are packed after the regions are measured, so the attributed total is the delta
    // before compression rather than an exact accounting of the patch on disk.
    assertEquals(report.regions.sumOf { it.patchBytes }, report.attributedBytes)
    assertTrue(report.attributedBytes > 0)
  }
}
