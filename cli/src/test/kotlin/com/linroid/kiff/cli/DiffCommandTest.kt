package com.linroid.kiff.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiffCommandTest {

  private val dir: File = Files.createTempDirectory("kiff-diff").toFile()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun file(name: String, bytes: ByteArray): String =
    File(dir, name).apply { writeBytes(bytes) }.path

  private fun file(name: String, text: String): String = file(name, text.encodeToByteArray())

  private class Run(val status: Int, val out: ByteArray)

  private fun kiff(vararg args: String): Run {
    val out = ByteArrayOutputStream()
    val status = runKiff(arrayOf(*args), out)
    return Run(status, out.toByteArray())
  }

  @Test
  fun identicalFilesExitZeroAndPrintNothing() {
    val run = kiff("diff", file("a", "x\n"), file("b", "x\n"))
    assertEquals(0, run.status)
    assertEquals(0, run.out.size)
  }

  @Test
  fun differentFilesExitOneWithTheirDiff() {
    val run = kiff("diff", file("a", "x\n"), file("b", "y\n"))
    assertEquals(1, run.status)
    assertTrue(run.out.decodeToString().startsWith("--- "), run.out.decodeToString())
  }

  @Test
  fun aMissingFileIsTroubleRatherThanADifference() {
    val run = kiff("diff", file("a", "x\n"), File(dir, "missing").path)
    assertEquals(2, run.status)
  }

  @Test
  fun aNegativeContextIsTrouble() {
    val run = kiff(
      "diff", "-U", "-1",
      file("a", "x\n"), file("b", "y\n")
    )
    assertEquals(2, run.status)
    assertEquals(0, run.out.size)
  }

  @Test
  fun tabsAndBytesInAnyEncodingPassThroughUnchanged() {
    // Latin-1 é and è: as UTF-8 both decoded to the same replacement character, so these two
    // files used to print nothing and exit 0. The tab was expanded on the way out, so patch(1)
    // refused any diff of an indented makefile.
    val source = "all:\n\techo hi\ncaf".encodeToByteArray() + byteArrayOf(0xE9.toByte(), 0x0A)
    val target = "all:\n\techo bye\ncaf".encodeToByteArray() + byteArrayOf(0xE8.toByte(), 0x0A)
    val run = kiff("diff", file("a", source), file("b", target))

    assertEquals(1, run.status)
    assertTrue(run.out.contains("-\techo hi\n".encodeToByteArray()))
    assertTrue(run.out.contains("-caf".encodeToByteArray() + byteArrayOf(0xE9.toByte(), 0x0A)))
    assertTrue(run.out.contains("+caf".encodeToByteArray() + byteArrayOf(0xE8.toByte(), 0x0A)))
    assertTrue(!run.out.contains(byteArrayOf(0xEF.toByte(), 0xBF.toByte(), 0xBD.toByte())))
  }

  @Test
  fun binaryFilesAreReportedRatherThanPrinted() {
    val a = file("a.bin", byteArrayOf(1, 0, 27, 2))
    val b = file("b.bin", byteArrayOf(1, 0, 27, 3))
    val run = kiff("diff", a, b)
    assertEquals(1, run.status)
    assertEquals("Binary files $a and $b differ\n", run.out.decodeToString())
  }

  @Test
  fun noCommandAtAllIsAFailure() {
    assertNotEquals(0, kiff().status)
  }

  private fun ByteArray.contains(part: ByteArray): Boolean =
    (0..size - part.size).any { at -> part.indices.all { this[at + it] == part[it] } }
}
