package com.linroid.kiff.format

import com.linroid.kiff.KiffException
import com.linroid.kiff.structuredBytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class LzHuffmanTest {

  private fun roundTrip(data: ByteArray, what: String): Int {
    val packed = LzHuffman.compress(data)
    val back = LzHuffman.decompress(packed, data.size)
    assertContentEquals(data, back, "$what did not come back")
    return packed.size
  }

  @Test
  fun noStreamUnpacksToMoreThanItsBoundAllows() {
    // The reader refuses a declared size past maxUnpackedSize before allocating it, so the bound
    // has to hold for the most compressible input there is, or it would refuse real patches.
    for (data in listOf(ByteArray(4_000_000), ByteArray(1_000_000) { (it % 3).toByte() })) {
      val packed = LzHuffman.compress(data)
      assertTrue(
        LzHuffman.maxUnpackedSize(packed.size) >= data.size,
        "${data.size} bytes packed to ${packed.size}, which the bound says hold only " +
          "${LzHuffman.maxUnpackedSize(packed.size)}"
      )
    }
    assertEquals(0L, LzHuffman.maxUnpackedSize(4), "four bytes cannot even hold the code lengths")
  }

  /**
   * The stream that unpacks to the most bytes for its length: one literal, then [matches] matches
   * of the longest length at distance one, each a one-bit length symbol, its ten extra bits and a
   * one-bit distance symbol.
   */
  private fun densest(matches: Int): ByteArray {
    val bits = StringBuilder()
    // Code lengths, four bits each: literal 0 and the end symbol two bits, the longest length
    // symbol one bit; distance 1 one bit. Every other symbol is unused.
    for (symbol in 0 until 289) {
      bits.append(lengthBits(if (symbol == 0 || symbol == 256) 2 else if (symbol == 288) 1 else 0))
    }
    for (symbol in 0 until 40) bits.append(lengthBits(if (symbol == 0) 1 else 0))
    bits.append("10")
    repeat(matches) { bits.append("0").append("1".repeat(10)).append("0") }
    bits.append("11")
    while (bits.length % 8 != 0) bits.append('0')
    return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
  }

  private fun lengthBits(length: Int) = length.toString(2).padStart(4, '0')

  @Test
  fun theBoundIsCloseToTheDensestStream() {
    // The bound used to allow six times what any stream can hold: it priced a 4,119-byte match at
    // two bits, when its length alone carries ten extra ones.
    for (matches in listOf(1, 10, 1_000)) {
      val stream = densest(matches)
      val size = 1 + 4_119 * matches
      assertContentEquals(ByteArray(size), LzHuffman.decompress(stream, size))
      val bound = LzHuffman.maxUnpackedSize(stream.size)
      assertTrue(bound >= size, "bound $bound refuses a stream of $size")
      assertTrue(bound - size < 2 * 4_119, "bound $bound is loose for a stream of $size")
    }
  }

  @Test
  fun everyShapeOfInputRoundTrips() {
    roundTrip(ByteArray(0), "empty")
    roundTrip(byteArrayOf(7), "one byte")
    roundTrip(ByteArray(3) { it.toByte() }, "shorter than a match")
    roundTrip(ByteArray(1024), "all zeroes")
    roundTrip(ByteArray(300_000), "a long run")
    roundTrip(ByteArray(2048) { (it % 251).toByte() }, "a repeating cycle")
    roundTrip(structuredBytes(64_000, seed = 1), "structured bytes")
    roundTrip(Random(2).nextBytes(64_000), "incompressible noise")
    roundTrip(ByteArray(5000) { if (it % 97 == 0) 0x41 else 0 }, "sparse, like a difference run")
  }

  @Test
  fun aSingleRepeatedByteRoundTrips() {
    // One symbol in the whole alphabet: the degenerate Huffman tree, where a naive builder gives a
    // zero-length code and the decoder never terminates.
    for (size in listOf(1, 2, 5, 64, 100_000)) {
      roundTrip(ByteArray(size) { 0x5A }, "$size copies of one byte")
    }
  }

  @Test
  fun everyByteValueSurvives() {
    roundTrip(ByteArray(256) { it.toByte() }, "each byte once")
    roundTrip(ByteArray(256 * 40) { (it % 256).toByte() }, "each byte many times")
  }

  @Test
  fun extremelySkewedInputStillFitsTheCodeLengthLimit() {
    // One byte overwhelmingly common and the rest vanishingly rare is what pushes a Huffman tree
    // past the depth the format can write down.
    val random = Random(3)
    val data = ByteArray(200_000) { if (random.nextInt(2000) == 0) random.nextInt(256).toByte() else 0 }
    roundTrip(data, "heavily skewed")
  }

  @Test
  fun itBeatsTheOlderCodecOnRealisticContent() {
    val data = structuredBytes(400_000, seed = 4)
    val old = Lzss.compress(data).size
    val new = LzHuffman.compress(data).size
    assertTrue(new < old, "entropy coding should pay: $new against $old")
  }

  @Test
  fun corruptedInputIsRefusedRatherThanDecodedIntoNonsense() {
    val data = structuredBytes(20_000, seed = 5)
    val packed = LzHuffman.compress(data)
    for (seed in 1..400) {
      val random = Random(seed)
      val broken = packed.copyOf()
      repeat(random.nextInt(1, 5)) {
        broken[random.nextInt(broken.size)] = random.nextInt(256).toByte()
      }
      try {
        val back = LzHuffman.decompress(broken, data.size)
        // Decoding something is allowed; decoding something wrong without complaint is not, and
        // the region checksums above this catch that. What must not happen is any other failure.
        assertTrue(back.size == data.size)
      } catch (e: KiffException) {
        // expected
      } catch (e: Throwable) {
        fail("seed $seed raised ${e::class.simpleName}: ${e.message}")
      }
    }
  }
}
