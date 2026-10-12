package com.linroid.kiff.text

import com.linroid.kiff.format.ByteWriter
import com.linroid.kiff.format.PatchPayload
import com.linroid.kiff.format.RegionEncoding
import com.linroid.kiff.io.ByteArraySource
import com.linroid.kiff.io.RestoreTarget
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a TEXT region costs in memory, measured rather than argued.
 *
 * A patch names the source range a TEXT region reads, and a range of newlines is all lines. Indexed
 * line by line, sixteen megabytes of them took more than twenty times that in heap, so a patch of a
 * few dozen bytes could exhaust a device's memory before a single byte was restored.
 */
class TextRegionMemoryJvmTest {

  private val newlines = ByteArray(32 shl 20) { '\n'.code.toByte() }

  private fun allocatedBy(block: () -> Unit): Long {
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val id = Thread.currentThread().id
    val before = threads.getThreadAllocatedBytes(id)
    block()
    return threads.getThreadAllocatedBytes(id) - before
  }

  @Test
  fun applyingHoldsTheRangeAndNoMore() {
    // A region of no bytes that reads the whole source and ends at once: 20-odd bytes of patch.
    val tree = ByteWriter(32).apply {
      writeVarLong(0)
      writeByte(RegionEncoding.TEXT.code)
      writeVarLong(0)
      writeVarLong(newlines.size.toLong())
      writeVarInt(1)
      writeByte(0)
    }.toByteArray()
    val payload = ByteWriter(32).apply {
      writeVarInt(tree.size)
      writeVarInt(0)
      writeByte(PatchPayload.CONTENT_STORED)
      writeVarInt(0)
      writeBytes(tree)
    }.toByteArray()

    val allocated = allocatedBy {
      PatchPayload.apply(
        ByteArraySource(newlines), payload, 0, 0, checksums = false,
        out = object : RestoreTarget {
          override fun write(bytes: ByteArray, from: Int, to: Int) = Unit
        }
      )
    }
    assertTrue(
      allocated < 2L * newlines.size,
      "applying a ${newlines.size}-byte range allocated $allocated bytes"
    )
  }

  @Test
  fun encodingTurnsDownTooManyLinesBeforeIndexingThem() {
    var result: ByteArray? = ByteArray(0)
    val allocated = allocatedBy {
      result = TextRegion.encode(
        MyersDiffAlgorithm(),
        newlines, 0, newlines.size,
        newlines, 0, newlines.size,
        ByteWriter(64)
      )
    }
    assertNull(result)
    assertTrue(allocated < 1L shl 20, "declining allocated $allocated bytes")
  }
}
