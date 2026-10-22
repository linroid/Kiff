package com.linroid.kiff.cli

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiffCommandTest {

  private val dir = TestDirectory()

  @AfterTest
  fun cleanUp() {
    dir.delete()
  }

  @Test
  fun identicalFilesExitZeroAndPrintNothing() {
    val run = capture("diff", dir.file("a", "x\n"), dir.file("b", "x\n"))
    assertEquals(0, run.status)
    assertEquals(0, run.bytes.size)
  }

  @Test
  fun differentFilesExitOneWithTheirDiff() {
    val run = capture("diff", dir.file("a", "x\n"), dir.file("b", "y\n"))
    assertEquals(1, run.status)
    assertTrue(run.bytes.decodeToString().startsWith("--- "), run.bytes.decodeToString())
  }

  @Test
  fun aMissingFileIsTroubleRatherThanADifference() {
    val run = capture("diff", dir.file("a", "x\n"), dir.resolve("missing").toString())
    assertEquals(2, run.status)
  }

  @Test
  fun aNegativeContextIsTrouble() {
    val run = capture(
      "diff", "-U", "-1",
      dir.file("a", "x\n"), dir.file("b", "y\n")
    )
    assertEquals(2, run.status)
    assertEquals(0, run.bytes.size)
  }

  @Test
  fun tabsAndBytesInAnyEncodingPassThroughUnchanged() {
    // Latin-1 é and è: as UTF-8 both decoded to the same replacement character, so these two
    // files used to print nothing and exit 0. The tab was expanded on the way out, so patch(1)
    // refused any diff of an indented makefile.
    val source = "all:\n\techo hi\ncaf".encodeToByteArray() + byteArrayOf(0xE9.toByte(), 0x0A)
    val target = "all:\n\techo bye\ncaf".encodeToByteArray() + byteArrayOf(0xE8.toByte(), 0x0A)
    val run = capture("diff", dir.file("a", source), dir.file("b", target))

    assertEquals(1, run.status)
    assertTrue(run.bytes.contains("-\techo hi\n".encodeToByteArray()))
    assertTrue(run.bytes.contains("-caf".encodeToByteArray() + byteArrayOf(0xE9.toByte(), 0x0A)))
    assertTrue(run.bytes.contains("+caf".encodeToByteArray() + byteArrayOf(0xE8.toByte(), 0x0A)))
    assertTrue(!run.bytes.contains(byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())))
  }

  @Test
  fun binaryFilesAreReportedRatherThanPrinted() {
    val a = dir.file("a.bin", byteArrayOf(1, 0, 27, 2))
    val b = dir.file("b.bin", byteArrayOf(1, 0, 27, 3))
    val run = capture("diff", a, b)
    assertEquals(1, run.status)
    assertEquals("Binary files $a and $b differ\n", run.bytes.decodeToString())
  }

  @Test
  fun noCommandAtAllIsAFailure() {
    assertNotEquals(0, capture().status)
  }

  private fun ByteArray.contains(part: ByteArray): Boolean =
    (0..size - part.size).any { at -> part.indices.all { this[at + it] == part[it] } }
}
