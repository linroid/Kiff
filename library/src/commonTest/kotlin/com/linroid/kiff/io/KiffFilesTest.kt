package com.linroid.kiff.io

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * The file helpers on every platform with a file system, where the JVM tests reach only one.
 *
 * Each platform brings its own way to keep a replaced file's permissions and to sync it to disk,
 * so the same replacement is exercised on each.
 */
class KiffFilesTest {

  private val fs = systemFileSystem
  private val dir: Path =
    FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kiff-${Random.nextLong().toULong().toString(16)}"

  init {
    fs.createDirectories(dir)
  }

  @AfterTest
  fun cleanUp() {
    fs.deleteRecursively(dir)
  }

  private fun leftovers() = fs.list(dir).filter { it.name.startsWith(".kiff-") }

  @Test
  fun aFileIsReplacedWhole() {
    val file = dir / "out.bin"
    KiffFiles.writeBytes(file.toString(), ByteArray(10_000) { 1 })
    KiffFiles.writeBytes(file.toString(), byteArrayOf(2, 3))
    assertContentEquals(byteArrayOf(2, 3), KiffFiles.readBytes(file.toString()))
    assertEquals(emptyList(), leftovers())
  }

  @Test
  fun aSymlinkIsFollowedToTheFileItNames() {
    val real = dir / "real.bin"
    val link = dir / "link.bin"
    KiffFiles.writeBytes(real.toString(), byteArrayOf(1))
    try {
      fs.createSymlink(link, real)
    } catch (e: IOException) {
      return // a platform or file system without symlinks
    }
    KiffFiles.writeBytes(link.toString(), byteArrayOf(2))
    assertNotNull(fs.metadata(link).symlinkTarget, "the link should still be a link")
    assertContentEquals(byteArrayOf(2), KiffFiles.readBytes(real.toString()))
    assertEquals(emptyList(), leftovers())
  }

  @Test
  fun aDirectoryIsRefusedBeforeAnythingIsWritten() {
    val folder = dir / "folder"
    fs.createDirectory(folder)
    var written = false
    val failure = runCatching {
      KiffFiles.writeStreaming(folder.toString()) { written = true }
    }.exceptionOrNull()
    assertTrue(failure is IOException, "expected an IOException, got $failure")
    assertTrue(!written, "the restore should not have run")
    assertEquals(emptyList(), fs.list(folder))
  }
}
