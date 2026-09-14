package com.linroid.kiff.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {

  @Test
  fun matchesKnownVectors() {
    assertEquals(0x00000000u, Crc32.compute(ByteArray(0)))
    assertEquals(0xE8B7BE43u, Crc32.compute("a".encodeToByteArray()))
    assertEquals(0x352441C2u, Crc32.compute("abc".encodeToByteArray()))
    assertEquals(0xCBF43926u, Crc32.compute("123456789".encodeToByteArray()))
  }

  @Test
  fun honoursRange() {
    val data = "xxabcxx".encodeToByteArray()
    assertEquals(0x352441C2u, Crc32.compute(data, 2, 5))
  }
}
