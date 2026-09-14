package com.linroid.kiff.io

import com.linroid.kiff.Kiff
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSourceJvmTest {

  private val dir = File(System.getProperty("java.io.tmpdir"), "kiff-${System.nanoTime()}")
    .apply { mkdirs() }

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  private fun write(name: String, bytes: ByteArray): File =
    File(dir, name).apply { writeBytes(bytes) }

  @Test
  fun readsAFileAtArbitraryOffsets() {
    val bytes = ByteArray(70_000) { (it % 251).toByte() }
    val file = write("data.bin", bytes)

    fileSource(file.path).use { source ->
      assertEquals(bytes.size.toLong(), source.size)
      val buffer = ByteArray(64)
      // Backwards on purpose: a delta reads its source out of order.
      for (position in longArrayOf(69_000, 1_000, 40_000, 0)) {
        source.readFully(position, buffer)
        assertContentEquals(
          bytes.copyOfRange(position.toInt(), position.toInt() + buffer.size),
          buffer,
          "mismatch at $position"
        )
      }
      assertEquals(-1, source.read(bytes.size.toLong(), ByteArray(1)))
    }
  }

  @Test
  fun patchesRoundTripThroughTheFileHelpers() {
    val source = ByteArray(120_000) { (it * 31 % 251).toByte() }
    val target = source.copyOf().also {
      it.fill(7, 60_000, 60_500)
    } + ByteArray(4_000) { (it % 97).toByte() }

    val sourceFile = write("old.bin", source)
    val targetFile = write("new.bin", target)
    val patchFile = File(dir, "delta.patch")
    val outputFile = File(dir, "restored.bin")

    val created = Kiff.createPatch(Kiff.binary, sourceFile.path, targetFile.path, patchFile.path)
    assertEquals(source.size.toLong(), created.sourceSize)
    assertEquals(target.size.toLong(), created.targetSize)
    assertTrue(created.patchSize < target.size / 4, "patch was ${created.patchSize} bytes")

    Kiff.applyPatch(sourceFile.path, patchFile.path, outputFile.path)
    assertContentEquals(target, outputFile.readBytes())
  }

  @Test
  fun aFileSourceAndAByteArraySourceProduceTheSamePatch() {
    val source = ByteArray(50_000) { (it % 199).toByte() }
    val target = source.copyOf().also { it[25_000] = 3 }
    val sourceFile = write("a.bin", source)
    val targetFile = write("b.bin", target)

    val fromFiles = fileSource(sourceFile.path).use { s ->
      fileSource(targetFile.path).use { t -> Kiff.binary.createPatch(s, t) }
    }
    assertContentEquals(Kiff.binary.createPatch(source, target), fromFiles)
  }
}
