package com.linroid.kiff.binary

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SuffixArrayTest {

  @Test
  fun emptyInput() {
    val sa = SuffixArray.build(ByteArray(0))
    assertEquals(0, sa.size)
  }

  @Test
  fun singleByte() {
    val sa = SuffixArray.build(byteArrayOf(42))
    assertContentEquals(intArrayOf(0), sa)
  }

  @Test
  fun bananaKnownCase() {
    // "banana" → suffixes sorted:
    // 5: "a"
    // 3: "ana"
    // 1: "anana"
    // 0: "banana"
    // 4: "na"
    // 2: "nana"
    val data = "banana".encodeToByteArray()
    val sa = SuffixArray.build(data)
    assertContentEquals(intArrayOf(5, 3, 1, 0, 4, 2), sa)
  }

  @Test
  fun repeatedBytes() {
    val data = byteArrayOf(1, 1, 1, 1)
    val sa = SuffixArray.build(data)
    assertEquals(4, sa.size)
    // Suffixes: [1,1,1,1], [1,1,1], [1,1], [1]
    // Sorted: [1] < [1,1] < [1,1,1] < [1,1,1,1]
    assertContentEquals(intArrayOf(3, 2, 1, 0), sa)
  }

  @Test
  fun lexicographicOrdering() {
    val data = byteArrayOf(3, 1, 2)
    val sa = SuffixArray.build(data)
    // Suffixes: 0:"3,1,2", 1:"1,2", 2:"2"
    // Sorted: "1,2"(1) < "2"(2) < "3,1,2"(0)
    assertContentEquals(intArrayOf(1, 2, 0), sa)
  }

  @Test
  fun allSuffixesArePresent() {
    val data = "hello".encodeToByteArray()
    val sa = SuffixArray.build(data)
    assertEquals(5, sa.size)
    // Each index 0..4 must appear exactly once
    val sorted = sa.sorted()
    assertContentEquals(listOf(0, 1, 2, 3, 4), sorted)
  }

  @Test
  fun suffixesAreSorted() {
    val data = "abracadabra".encodeToByteArray()
    val sa = SuffixArray.build(data)
    for (i in 0 until sa.size - 1) {
      val a = data.copyOfRange(sa[i], data.size)
      val b = data.copyOfRange(sa[i + 1], data.size)
      assertTrue(
        compareLexicographic(a, b) <= 0,
        "Suffix at sa[$i]=${sa[i]} should be <= suffix at sa[${i + 1}]=${sa[i + 1]}",
      )
    }
  }

  private fun compareLexicographic(a: ByteArray, b: ByteArray): Int {
    val minLen = minOf(a.size, b.size)
    for (i in 0 until minLen) {
      val av = a[i].toInt() and 0xFF
      val bv = b[i].toInt() and 0xFF
      if (av != bv) return av - bv
    }
    return a.size - b.size
  }
}
