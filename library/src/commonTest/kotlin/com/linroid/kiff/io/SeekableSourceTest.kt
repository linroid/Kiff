package com.linroid.kiff.io

import com.linroid.kiff.KiffException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeekableSourceTest {

  private val data = ByteArray(1_000) { (it % 251).toByte() }

  @Test
  fun readsFromAnyPosition() {
    val source = data.asSource()
    assertEquals(1_000L, source.size)
    val buffer = ByteArray(10)
    source.readFully(500, buffer)
    assertContentEquals(data.copyOfRange(500, 510), buffer)
  }

  @Test
  fun reportsEndOfInput() {
    val source = data.asSource()
    assertEquals(-1, source.read(1_000, ByteArray(4)))
    assertEquals(4, source.read(996, ByteArray(4)))
    // A read straddling the end is short, not an error.
    assertEquals(2, source.read(998, ByteArray(8)))
  }

  @Test
  fun readFullyRefusesToRunPastTheEnd() {
    assertFailsWith<KiffException.UnsupportedInput> {
      data.asSource().readFully(995, ByteArray(20))
    }
  }

  @Test
  fun emptySourceIsReadable() {
    val source = ByteArray(0).asSource()
    assertEquals(0L, source.size)
    assertEquals(-1, source.read(0, ByteArray(1)))
  }

  @Test
  fun anySourceImplementationWorksAsAPatchInput() {
    // Deliberately awkward: hands back one byte at a time, as a network-backed source might.
    val dribbling = object : SeekableSource {
      override val size: Long get() = data.size.toLong()
      override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        if (length <= 0) return 0
        into[offset] = data[position.toInt()]
        return 1
      }
    }
    val target = data.copyOf().also { it[10] = 99 }
    val patch = com.linroid.kiff.Kiff.binary.createPatch(dribbling, target.asSource())
    assertContentEquals(target, com.linroid.kiff.Kiff.binary.applyPatch(dribbling, patch))
  }
}
