package com.linroid.kiff.io

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.FileSystem
import okio.Path
import platform.posix.chmod
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
class FilePermissionsPosixTest {

  private val dir: Path =
    FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "kiff-${Random.nextLong().toULong().toString(16)}"

  init {
    systemFileSystem.createDirectories(dir)
  }

  @AfterTest
  fun cleanUp() {
    systemFileSystem.deleteRecursively(dir)
  }

  private fun modeOf(path: Path): Int = memScoped {
    val info = alloc<stat>()
    check(stat(path.toString(), info.ptr) == 0)
    info.st_mode.toInt() and 0xFFF
  }

  @Test
  fun aReplacedFileKeepsItsMode() {
    for (mode in listOf(0b111_101_000, 0b110_000_000, 0b100_100_100)) {
      val file = dir / "file-$mode"
      KiffFiles.writeBytes(file.toString(), byteArrayOf(1))
      chmod(file.toString(), mode.convert())

      KiffFiles.writeBytes(file.toString(), byteArrayOf(2, 3))
      assertEquals(mode, modeOf(file))
      assertContentEquals(byteArrayOf(2, 3), KiffFiles.readBytes(file.toString()))
    }
  }
}
