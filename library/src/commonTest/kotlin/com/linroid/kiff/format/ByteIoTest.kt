package com.linroid.kiff.format

import com.linroid.kiff.KiffException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ByteIoTest {

  @Test
  fun varIntsRoundTrip() {
    val values = listOf(0, 1, 127, 128, 300, 65535, 1 shl 20, Int.MAX_VALUE)
    val writer = ByteWriter()
    values.forEach { writer.writeVarInt(it) }
    val reader = ByteReader(writer.toByteArray())
    values.forEach { assertEquals(it, reader.readVarInt()) }
    assertEquals(0, reader.remaining)
  }

  @Test
  fun signedVarLongsRoundTrip() {
    val values = listOf(
      0L, -1L, 1L, -127L, 4096L, -70_000_000L, 70_000_000L,
      Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(),
      // Past 2 GB, which is the whole point of the 64-bit encoding.
      1L shl 31, -(1L shl 31), 1L shl 40, -(1L shl 40)
    )
    val writer = ByteWriter()
    values.forEach { writer.writeSignedVarLong(it) }
    val reader = ByteReader(writer.toByteArray())
    values.forEach { assertEquals(it, reader.readSignedVarLong()) }
  }

  @Test
  fun smallValuesStaySmall() {
    val writer = ByteWriter()
    writer.writeSignedVarLong(-3)
    assertEquals(1, writer.size)
  }

  @Test
  fun uInt32RoundTrips() {
    val writer = ByteWriter()
    writer.writeUInt32(0xDEADBEEFu)
    val bytes = writer.toByteArray()
    assertEquals(0xDE.toByte(), bytes[0])
    assertEquals(0xDEADBEEFu, ByteReader(bytes).readUInt32())
  }

  @Test
  fun bytesRoundTripThroughGrowth() {
    val data = Random(7).nextBytes(10_000)
    val writer = ByteWriter(16)
    var offset = 0
    while (offset < data.size) {
      val end = minOf(data.size, offset + 137)
      writer.writeBytes(data, offset, end)
      offset = end
    }
    assertTrue(data.contentEquals(writer.toByteArray()))
  }

  @Test
  fun readingPastTheEndFails() {
    val reader = ByteReader(byteArrayOf(1, 2))
    assertFailsWith<KiffException.InvalidPatch> { reader.readBytes(3) }
  }

  @Test
  fun aCountThatWouldWrapThePositionIsRefused() {
    // One byte in, Int.MAX_VALUE more wraps a sum negative, which is not past the end.
    val reader = ByteReader(byteArrayOf(1, 2, 3), position = 1)
    assertFailsWith<KiffException.InvalidPatch> { reader.skip(Int.MAX_VALUE) }
    assertFailsWith<KiffException.InvalidPatch> { reader.readBytes(Int.MAX_VALUE) }
  }

  @Test
  fun theWidestVarintTheWriterProducesStillReads() {
    val writer = ByteWriter()
    writer.writeVarLong(Long.MAX_VALUE)
    assertEquals(Long.MAX_VALUE, ByteReader(writer.toByteArray()).readVarLong())
  }

  @Test
  fun aVarintWiderThanSixtyFourBitsIsRefused() {
    val tooWide = ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x02)
    assertFailsWith<KiffException.InvalidPatch> { ByteReader(tooWide).readVarLong() }
  }

  @Test
  fun aVarintThatSetsTheTopBitIsNotALength() {
    // Ten bytes that decode to -1: a valid 64-bit value, and never a size, count or length.
    val negative = ByteArray(9) { 0xFF.toByte() } + byteArrayOf(0x01)
    assertEquals(-1L, ByteReader(negative).readVarLong())
    assertFailsWith<KiffException.InvalidPatch> { ByteReader(negative).readVarInt() }
  }
}
