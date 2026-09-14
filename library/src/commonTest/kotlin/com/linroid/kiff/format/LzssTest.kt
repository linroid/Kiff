package com.linroid.kiff.format

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class LzssTest {

  private fun roundTrip(data: ByteArray) {
    val packed = Lzss.compress(data)
    assertContentEquals(data, Lzss.decompress(packed, data.size))
  }

  @Test
  fun roundTripsEdgeCases() {
    roundTrip(ByteArray(0))
    roundTrip(byteArrayOf(1))
    roundTrip(byteArrayOf(1, 2, 3))
    roundTrip(ByteArray(4))
  }

  @Test
  fun roundTripsRepetitiveData() {
    roundTrip(ByteArray(100_000))
    roundTrip(ByteArray(100_000) { (it % 7).toByte() })
    roundTrip("abcabcabcabc".repeat(5000).encodeToByteArray())
  }

  @Test
  fun roundTripsIncompressibleData() {
    roundTrip(Random(11).nextBytes(200_000))
  }

  @Test
  fun roundTripsMixedData() {
    val random = Random(3)
    val out = ByteWriter()
    repeat(40) {
      out.writeBytes(random.nextBytes(500))
      out.writeBytes(ByteArray(2000))
      out.writeBytes("the quick brown fox ".repeat(30).encodeToByteArray())
    }
    roundTrip(out.toByteArray())
  }

  @Test
  fun compressesRedundancy() {
    val data = "kotlin multiplatform ".repeat(5000).encodeToByteArray()
    val packed = Lzss.compress(data)
    assertTrue(packed.size < data.size / 50, "expected strong compression, got ${packed.size}")
  }

  @Test
  fun spansTheWindowBoundary() {
    val random = Random(5)
    val block = random.nextBytes(70_000)
    roundTrip(block + block)
  }
}
