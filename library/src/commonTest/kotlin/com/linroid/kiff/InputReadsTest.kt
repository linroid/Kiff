package com.linroid.kiff

import com.linroid.kiff.format.Crc32
import com.linroid.kiff.io.SeekableSource
import com.linroid.kiff.io.asSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** How creating a patch reads the sources it is handed. */
class InputReadsTest {

  /** A source that is not a byte array, counting what is read from it. */
  private open class Counting(private val bytes: ByteArray) : SeekableSource {
    var read = 0L
      private set

    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
      val count = bytes.asSource().read(position, into, offset, length)
      if (count > 0) read += count
      return count
    }
  }

  private val source = structuredBytes(200_000, seed = 1)
  private val target = source.copyOf().also { it[100_000] = (it[100_000] + 1).toByte() }

  @Test
  fun eachInputIsReadOnce() {
    // A checksum pass and then a second read for the search: twice the I/O, and on a source over
    // a network twice the transfer.
    for (patcher in Kiff.patchers) {
      val from = Counting(source)
      val to = Counting(target)
      patcher.createPatch(from, to)
      assertEquals(source.size.toLong(), from.read, "${patcher.name} read the source")
      assertEquals(target.size.toLong(), to.read, "${patcher.name} read the target")
    }
  }

  @Test
  fun anInputTooLargeToIndexIsRefusedBeforeEitherIsRead() {
    val huge = object : Counting(ByteArray(0)) {
      override val size: Long get() = 3L shl 30
    }
    val small = Counting(target)
    assertFailsWith<KiffException.UnsupportedInput> { Kiff.binary.createPatch(small, huge) }
    assertEquals(0L, small.read, "the source was read before the target was refused")
    assertEquals(0L, huge.read)
  }

  /** Answers 0 once, at [at], the way a source with nothing ready yet might. */
  private class StallsOnce(bytes: ByteArray, private val at: Long) : Counting(bytes) {
    private var stalled = false

    override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
      if (position == at && !stalled) {
        stalled = true
        return 0
      }
      return super.read(position, into, offset, length)
    }
  }

  @Test
  fun aChecksumOfPartOfASourceIsNeverTakenForTheWhole() {
    // The checksum stopped at the first read of nothing and returned what it had: create wrote
    // the checksum of a prefix into the header, and every apply then refused the patch.
    assertFailsWith<KiffException.UnsupportedInput> {
      Crc32.compute(StallsOnce(source, at = 65_536))
    }
    val patch = Kiff.binary.createPatch(StallsOnce(source, at = 65_536), target.asSource())
    assertContentEquals(target, Kiff.binary.applyPatch(source, patch))
  }
}
