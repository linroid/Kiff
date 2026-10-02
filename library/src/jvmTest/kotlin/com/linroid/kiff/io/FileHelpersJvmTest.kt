package com.linroid.kiff.io

import com.linroid.kiff.Kiff
import com.linroid.kiff.KiffException
import com.linroid.kiff.format.ByteReader
import com.linroid.kiff.format.PatchFormat
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the path-level helpers leave on disk when a restore is refused.
 *
 * A streamed restore can only be verified once its bytes are written, so the helpers write beside
 * the destination and move the result into place only when it verified. These are the cases where
 * writing straight to the destination used to cost a file.
 */
class FileHelpersJvmTest {

  private val dir = File(System.getProperty("java.io.tmpdir"), "kiff-${System.nanoTime()}")
    .apply { mkdirs() }

  private val source = ByteArray(80_000) { (it * 31 % 251).toByte() }
  private val target = source.copyOf().also {
    it.fill(7, 40_000, 40_600)
  } + ByteArray(3_000) { (it % 89).toByte() }

  private val previous = "what was here before".encodeToByteArray()

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun write(name: String, bytes: ByteArray): File =
    File(dir, name).apply { writeBytes(bytes) }

  private fun assertNoTemporaryFilesLeft() {
    val leftovers = dir.listFiles().orEmpty().filter { it.name.startsWith(".kiff-") }
    assertTrue(leftovers.isEmpty(), "temporary files left behind: $leftovers")
  }

  @Test
  fun aPatchForAnotherSourceLeavesTheOutputAsItWas() {
    val patch = write("delta.patch", Kiff.binary.createPatch(source, target))
    val wrong = write("other.bin", source.copyOf().also { it[10] = (it[10] + 1).toByte() })
    val output = write("restored.bin", previous)

    assertFailsWith<KiffException.SourceMismatch> {
      Kiff.applyPatch(wrong.path, patch.path, output.path)
    }
    assertContentEquals(previous, output.readBytes())
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun aRestoreRefusedAfterItsLastByteLeavesTheOutputAsItWas() {
    // Break only the target checksum in the header: every region restores, every byte is written,
    // and the refusal comes at the very end - the case a streamed restore cannot take back.
    val bytes = Kiff.binary.createPatch(source, target)
    val header = ByteReader(bytes).also { PatchFormat.readHeader(it) }
    val checksumAt = header.offset - 4
    bytes[checksumAt] = (bytes[checksumAt] + 1).toByte()
    val patch = write("delta.patch", bytes)
    val input = write("old.bin", source)
    val output = write("restored.bin", previous)

    assertFailsWith<KiffException.VerificationFailed> {
      Kiff.applyPatch(input.path, patch.path, output.path)
    }
    assertContentEquals(previous, output.readBytes())
    assertNoTemporaryFilesLeft()

    val absent = File(dir, "absent.bin")
    assertFailsWith<KiffException.VerificationFailed> {
      Kiff.applyPatch(input.path, patch.path, absent.path)
    }
    assertTrue(!absent.exists(), "a refused restore should not create its output")
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun aFileCanBeUpdatedInPlace() {
    val patch = write("delta.patch", Kiff.binary.createPatch(source, target))
    val file = write("app.bin", source)

    Kiff.applyPatch(file.path, patch.path, file.path)
    assertContentEquals(target, file.readBytes())
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun aRefusedUpdateInPlaceLeavesTheSourceIntact() {
    // Writing straight to the output truncated it first, and here the output is the source.
    val patch = write("delta.patch", Kiff.binary.createPatch(target, source))
    val file = write("app.bin", source)

    assertFailsWith<KiffException.SourceMismatch> {
      Kiff.applyPatch(file.path, patch.path, file.path)
    }
    assertContentEquals(source, file.readBytes())
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun aPatchWrittenOverALongerFileReplacesItWhole() {
    val input = write("old.bin", source)
    val updated = write("new.bin", target)
    val patch = write("delta.patch", ByteArray(200_000) { 0x5A })

    val info = Kiff.createPatch(Kiff.binary, input.path, updated.path, patch.path)
    assertContentEquals(Kiff.binary.createPatch(source, target), patch.readBytes())
    assertEquals(patch.length(), info.patchSize)
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun anUpdateInPlaceKeepsTheFileMode() {
    // The replacement is a new file, and it used to get the default mode: an executable updated
    // in place could no longer run, and a private file became readable by everyone.
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
    val patch = write("delta.patch", Kiff.binary.createPatch(source, target))
    for (mode in listOf("rwxr-x---", "rw-------", "r--r--r--")) {
      val file = write("app-$mode.bin", source)
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(mode))

      Kiff.applyPatch(file.path, patch.path, file.path)
      val kept = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))
      assertEquals(mode, kept)
      assertContentEquals(target, file.readBytes())
    }
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun anUpdateThroughASymlinkUpdatesTheFileItNames() {
    // The link itself used to be replaced by a regular file, leaving what it named unchanged.
    val patch = write("delta.patch", Kiff.binary.createPatch(source, target))
    val real = write("app-1.0.bin", source)
    val link = File(dir, "app.bin").toPath()
    Files.createSymbolicLink(link, real.toPath().fileName)

    Kiff.applyPatch(link.toString(), patch.path, link.toString())
    assertTrue(Files.isSymbolicLink(link), "the link should still be a link")
    assertContentEquals(target, real.readBytes())
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun aNameNearTheLimitCanStillBeWritten() {
    // The temporary file was the destination's name plus 26 characters, past the limit for a name
    // that is itself within it.
    val file = File(dir, "a".repeat(250))
    KiffFiles.writeBytes(file.path, target)
    assertContentEquals(target, file.readBytes())
    assertNoTemporaryFilesLeft()
  }

  @Test
  fun aDirectoryIsRefusedByTheNameItWasGiven() {
    val patch = write("delta.patch", Kiff.binary.createPatch(source, target))
    val input = write("old.bin", source)
    val folder = File(dir, "out").apply { mkdirs() }
    for (name in listOf(folder.path, folder.path + "/")) {
      val failure = assertFailsWith<IOException> { Kiff.applyPatch(input.path, patch.path, name) }
      assertTrue(failure.message.orEmpty().startsWith("Cannot write $name"), failure.message)
      assertTrue(folder.listFiles().orEmpty().isEmpty(), "nothing should be written inside it")
    }
    assertNoTemporaryFilesLeft()
  }
}
