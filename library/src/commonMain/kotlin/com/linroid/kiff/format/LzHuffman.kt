package com.linroid.kiff.format

import com.linroid.kiff.KiffException

/**
 * The codec the content stream is packed with: LZ77 matching, then Huffman coding of what it found.
 *
 * [Lzss] does the first half and stops. It writes its tokens as varints, which spends a whole byte
 * on a literal however common that byte is, and a whole byte on a length however predictable. On a
 * real patch that leaves about a fifth of the content on the table: 4.7 MB where gzip manages 3.7
 * from the same input, and the difference is entirely the entropy coding gzip does and it does not.
 *
 * Nothing here is bit-compatible with deflate, and it does not need to be - this codec is internal
 * to the patch format, which is what makes it a few hundred lines rather than a few thousand. The
 * bucket tables are deflate's, though, because they are well chosen and there is nothing to gain by
 * inventing others.
 *
 * ## What is left, measured on the content stream of a real patch
 *
 * ```
 * this codec          3,873,349   40.9% of the raw stream
 * gzip -9             3,694,413   -4.6% against this
 * zstd -19            3,244,018  -16.2%
 * brotli -q 11        3,054,043  -21.2%
 * xz -9e              2,999,532  -22.6%
 * ```
 *
 * Three things follow, and each of them cost an experiment to learn.
 *
 * Being within 4.6% of gzip is the Huffman layer doing its job; closing the rest means lazy
 * matching, deeper chains, and coding the code lengths rather than writing them flat, which is
 * fiddly work for very little.
 *
 * A larger window is not the lever, however tempting it looks with a 1 MiB window over a 9 MB
 * stream. Giving zstd a 128 MB window instead of its usual one is worth 0.04%: a patch's content
 * is differences and literals, which repeat locally and not across megabytes.
 *
 * The remaining fifth is the entropy coder itself. What xz, brotli and zstd have that this does
 * not is a range coder driven by context models, rather than one static Huffman tree per alphabet.
 * That is a substantially larger codec than this one, and it is where the next 20% is - not in the
 * matching, not in the window, and not in splitting the stream up, which [PatchPayload] records
 * the measurement for.
 */
internal object LzHuffman {

  fun compress(data: ByteArray): ByteArray {
    if (data.isEmpty()) return ByteArray(0)
    val tokens = Tokens(data.size)
    match(data, tokens)

    val literalLengths = codeLengths(tokens.literalFrequencies, MAX_CODE_BITS)
    val distanceLengths = codeLengths(tokens.distanceFrequencies, MAX_CODE_BITS)

    val out = BitWriter(data.size / 3 + 64)
    for (length in literalLengths) out.write(length, CODE_LENGTH_BITS)
    for (length in distanceLengths) out.write(length, CODE_LENGTH_BITS)

    val literalCodes = canonicalCodes(literalLengths)
    val distanceCodes = canonicalCodes(distanceLengths)
    tokens.replay { symbol, extraBits, extraValue, distanceSymbol, distanceExtraBits, distanceExtra ->
      out.write(literalCodes[symbol], literalLengths[symbol])
      if (extraBits > 0) out.write(extraValue, extraBits)
      if (distanceSymbol >= 0) {
        out.write(distanceCodes[distanceSymbol], distanceLengths[distanceSymbol])
        if (distanceExtraBits > 0) out.write(distanceExtra, distanceExtraBits)
      }
    }
    out.write(literalCodes[END], literalLengths[END])
    return out.toByteArray()
  }

  fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
    val result = ByteArray(expectedSize)
    if (expectedSize == 0) return result
    val bits = BitReader(data)

    val literalLengths = IntArray(LITERAL_SYMBOLS) { bits.read(CODE_LENGTH_BITS) }
    val distanceLengths = IntArray(DISTANCE_SYMBOLS) { bits.read(CODE_LENGTH_BITS) }
    val literals = Decoder(literalLengths)
    val distances = Decoder(distanceLengths)

    var position = 0
    while (true) {
      val symbol = literals.decode(bits)
      when {
        symbol == END -> break
        symbol < LITERALS -> {
          if (position >= expectedSize) overflow(expectedSize)
          result[position++] = symbol.toByte()
        }
        else -> {
          val lengthIndex = symbol - LITERALS - 1
          if (lengthIndex >= LENGTH_BASE.size) {
            throw KiffException.InvalidPatch("Unknown length symbol $symbol in the content stream")
          }
          val length = LENGTH_BASE[lengthIndex] + bits.read(LENGTH_EXTRA[lengthIndex])
          val distanceIndex = distances.decode(bits)
          if (distanceIndex >= DISTANCE_BASE.size) {
            throw KiffException.InvalidPatch("Unknown distance symbol in the content stream")
          }
          val distance = DISTANCE_BASE[distanceIndex] + bits.read(DISTANCE_EXTRA[distanceIndex])
          if (distance > position) {
            throw KiffException.InvalidPatch("Content back-reference $distance before the start")
          }
          if (position + length > expectedSize) overflow(expectedSize)
          var from = position - distance
          repeat(length) { result[position++] = result[from++] }
        }
      }
    }
    if (position != expectedSize) {
      throw KiffException.InvalidPatch("Content stream produced $position of $expectedSize bytes")
    }
    return result
  }

  private fun overflow(expectedSize: Int): Nothing =
    throw KiffException.InvalidPatch("Content stream overflows $expectedSize bytes")

  // ---- matching -------------------------------------------------------------------------

  /**
   * What the match finder found, kept as symbols rather than bytes because the Huffman trees have
   * to be built from the whole stream before any of it can be written.
   */
  private class Tokens(size: Int) {
    val literalFrequencies = IntArray(LITERAL_SYMBOLS)
    val distanceFrequencies = IntArray(DISTANCE_SYMBOLS)

    // Literals and matches, in order: a literal as its byte, a match as its two symbols and extras.
    private var symbols = IntArray(maxOf(64, size / 2))
    private var count = 0

    init {
      literalFrequencies[END] = 1
    }

    fun literal(byte: Int) {
      add(byte); add(-1); add(0); add(0); add(0)
      literalFrequencies[byte]++
    }

    fun match(length: Int, distance: Int) {
      val lengthIndex = lengthIndexOf(length)
      val symbol = LITERALS + 1 + lengthIndex
      val distanceIndex = distanceIndexOf(distance)
      add(symbol)
      add(distanceIndex)
      add(LENGTH_EXTRA[lengthIndex])
      add(length - LENGTH_BASE[lengthIndex])
      add(distance - DISTANCE_BASE[distanceIndex])
      literalFrequencies[symbol]++
      distanceFrequencies[distanceIndex]++
    }

    inline fun replay(emit: (Int, Int, Int, Int, Int, Int) -> Unit) {
      var at = 0
      while (at < count) {
        val symbol = symbols[at]
        val distanceSymbol = symbols[at + 1]
        val extraBits = symbols[at + 2]
        val extraValue = symbols[at + 3]
        val distanceExtra = symbols[at + 4]
        emit(
          symbol, extraBits, extraValue, distanceSymbol,
          if (distanceSymbol >= 0) DISTANCE_EXTRA[distanceSymbol] else 0,
          distanceExtra
        )
        at += 5
      }
    }

    private fun add(value: Int) {
      if (count == symbols.size) symbols = symbols.copyOf(symbols.size * 2)
      symbols[count++] = value
    }
  }

  /** The same hash-chain search [Lzss] uses; only what is done with the result differs. */
  private fun match(data: ByteArray, tokens: Tokens) {
    val head = IntArray(HASH_SIZE) { -1 }
    val window = windowFor(data.size)
    val windowMask = window - 1
    val prev = IntArray(window)
    var position = 0

    while (position < data.size) {
      if (position + MIN_MATCH > data.size) {
        while (position < data.size) tokens.literal(data[position++].toInt() and 0xFF)
        break
      }
      val hash = hash4(data, position)
      var candidate = head[hash]
      var bestLength = 0
      var bestDistance = 0
      var chain = 0
      while (candidate >= 0 && chain < MAX_CHAIN) {
        val distance = position - candidate
        if (distance <= 0 || distance > window) break
        val length = matchLength(data, candidate, position)
        if (length > bestLength) {
          bestLength = length
          bestDistance = distance
          if (length >= MAX_MATCH) break
        }
        candidate = prev[candidate and windowMask]
        chain++
      }
      prev[position and windowMask] = head[hash]
      head[hash] = position

      if (bestLength >= MIN_MATCH) {
        val length = minOf(bestLength, MAX_MATCH)
        tokens.match(length, bestDistance)
        for (i in position + 1 until position + length) {
          if (i + MIN_MATCH > data.size) break
          val h = hash4(data, i)
          prev[i and windowMask] = head[h]
          head[h] = i
        }
        position += length
      } else {
        tokens.literal(data[position].toInt() and 0xFF)
        position++
      }
    }
  }

  // ---- Huffman --------------------------------------------------------------------------

  /**
   * Code lengths for [frequencies], none longer than [limit].
   *
   * The limit is not decoration: the decoder reads a code length in a fixed number of bits, and an
   * unbounded tree on skewed input can want more than that. Halving the counts and rebuilding
   * flattens the tree until it fits, which costs a fraction of a percent and always terminates.
   */
  private fun codeLengths(frequencies: IntArray, limit: Int): IntArray {
    var counts = frequencies
    while (true) {
      val lengths = huffmanLengths(counts)
      if (lengths.max() <= limit) return lengths
      counts = IntArray(counts.size) { if (counts[it] == 0) 0 else (counts[it] + 1) / 2 }
    }
  }

  private fun huffmanLengths(frequencies: IntArray): IntArray {
    val used = frequencies.indices.filter { frequencies[it] > 0 }
    val lengths = IntArray(frequencies.size)
    if (used.isEmpty()) return lengths
    if (used.size == 1) {
      lengths[used[0]] = 1
      return lengths
    }

    // A straightforward two-queue merge: nodes by weight, smallest two joined each round.
    val weight = ArrayList<Int>(used.size * 2)
    val left = ArrayList<Int>(used.size * 2)
    val right = ArrayList<Int>(used.size * 2)
    val leaves = used.sortedBy { frequencies[it] }
    for (symbol in leaves) {
      weight.add(frequencies[symbol])
      left.add(-1 - symbol)
      right.add(-1)
    }
    var next = 0
    val pending = ArrayDeque<Int>()
    var merged = 0
    fun take(): Int {
      val fromLeaves = next < leaves.size
      val fromPending = pending.isNotEmpty()
      return when {
        fromLeaves && (!fromPending || weight[next] <= weight[pending.first()]) -> next++
        fromPending -> pending.removeFirst()
        else -> error("nothing left to merge")
      }
    }
    while (next < leaves.size || pending.size > 1 || merged == 0) {
      if (next >= leaves.size && pending.size == 1) break
      val a = take()
      val b = take()
      weight.add(weight[a] + weight[b])
      left.add(a)
      right.add(b)
      pending.addLast(weight.size - 1)
      merged++
    }

    fun assign(node: Int, depth: Int) {
      var stack = mutableListOf(node to depth)
      while (stack.isNotEmpty()) {
        val (current, currentDepth) = stack.removeAt(stack.lastIndex)
        if (left[current] < 0 && right[current] < 0) {
          lengths[-1 - left[current]] = maxOf(1, currentDepth)
          continue
        }
        stack.add(left[current] to currentDepth + 1)
        stack.add(right[current] to currentDepth + 1)
      }
    }
    assign(weight.size - 1, 0)
    return lengths
  }

  /** Canonical codes: shorter lengths first, and within a length in symbol order. */
  private fun canonicalCodes(lengths: IntArray): IntArray {
    val counts = IntArray(MAX_CODE_BITS + 1)
    for (length in lengths) if (length > 0) counts[length]++
    val nextCode = IntArray(MAX_CODE_BITS + 2)
    var code = 0
    for (bits in 1..MAX_CODE_BITS) {
      code = (code + counts[bits - 1]) shl 1
      nextCode[bits] = code
    }
    return IntArray(lengths.size) { symbol ->
      val length = lengths[symbol]
      if (length == 0) 0 else nextCode[length]++
    }
  }

  /** Canonical decoder: counts per length, and the symbols of each length in order. */
  private class Decoder(lengths: IntArray) {
    private val counts = IntArray(MAX_CODE_BITS + 1)
    private val symbols: IntArray

    init {
      for (length in lengths) {
        if (length < 0 || length > MAX_CODE_BITS) {
          throw KiffException.InvalidPatch("Content stream declares a code length of $length")
        }
        if (length > 0) counts[length]++
      }
      // An over-subscribed tree decodes to nonsense rather than failing, so refuse it up front.
      var available = 1
      for (bits in 1..MAX_CODE_BITS) {
        available = available shl 1
        available -= counts[bits]
        if (available < 0) {
          throw KiffException.InvalidPatch("Content stream declares an impossible Huffman tree")
        }
      }
      // Where the symbols of each length start, which is also the order decode walks them in.
      val offsets = IntArray(MAX_CODE_BITS + 2)
      for (bits in 1..MAX_CODE_BITS) offsets[bits + 1] = offsets[bits] + counts[bits]
      symbols = IntArray(lengths.count { it > 0 })
      val cursor = offsets.copyOf()
      for (symbol in lengths.indices) {
        val length = lengths[symbol]
        if (length > 0) symbols[cursor[length]++] = symbol
      }
    }

    fun decode(bits: BitReader): Int {
      var code = 0
      var first = 0
      var index = 0
      for (length in 1..MAX_CODE_BITS) {
        code = code or bits.read(1)
        val count = counts[length]
        if (code - first < count) return symbols[index + (code - first)]
        index += count
        first = (first + count) shl 1
        code = code shl 1
      }
      throw KiffException.InvalidPatch("Content stream holds a code no tree describes")
    }
  }

  // ---- bits -----------------------------------------------------------------------------

  /** Most significant bit first, which is the order canonical Huffman codes are built in. */
  private class BitWriter(capacity: Int) {
    private val out = ByteWriter(capacity)
    private var bits = 0
    private var count = 0

    fun write(value: Int, length: Int) {
      for (i in length - 1 downTo 0) {
        bits = (bits shl 1) or ((value ushr i) and 1)
        count++
        if (count == 8) {
          out.writeByte(bits and 0xFF)
          bits = 0
          count = 0
        }
      }
    }

    fun toByteArray(): ByteArray {
      if (count > 0) out.writeByte((bits shl (8 - count)) and 0xFF)
      return out.toByteArray()
    }
  }

  private class BitReader(private val data: ByteArray) {
    private var at = 0
    private var bits = 0
    private var count = 0

    fun read(length: Int): Int {
      var value = 0
      repeat(length) {
        if (count == 0) {
          if (at >= data.size) {
            throw KiffException.InvalidPatch("Content stream ended mid-symbol")
          }
          bits = data[at++].toInt() and 0xFF
          count = 8
        }
        value = (value shl 1) or ((bits ushr (count - 1)) and 1)
        count--
      }
      return value
    }
  }

  // ---- tables ---------------------------------------------------------------------------

  private const val LITERALS = 256
  private const val END = LITERALS
  private const val MIN_MATCH = 4
  private const val MAX_CODE_BITS = 15
  private const val CODE_LENGTH_BITS = 4
  private const val MAX_WINDOW = 1 shl 20
  private const val HASH_BITS = 17
  private const val HASH_SIZE = 1 shl HASH_BITS
  private const val MAX_CHAIN = 24

  private val LENGTH_BASE = intArrayOf(
    4, 5, 6, 7, 8, 9, 10, 11,
    12, 14, 16, 18,
    20, 24, 28,
    32, 40, 48,
    56, 72,
    88, 120,
    152, 216,
    280, 408,
    536, 792,
    1048, 1560,
    2072, 3096
  )
  private val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1,
    2, 2, 2,
    3, 3, 3,
    4, 4,
    5, 5,
    6, 6,
    7, 7,
    8, 8,
    9, 9,
    10, 10
  )
  private val MAX_MATCH = LENGTH_BASE.last() + (1 shl LENGTH_EXTRA.last()) - 1

  private val DISTANCE_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
    257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289,
    16385, 24577, 32769, 49153, 65537, 98305, 131073, 196609, 262145, 393217,
    524289, 786433
  )
  private val DISTANCE_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
    7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12,
    13, 13, 14, 14, 15, 15, 16, 16, 17, 17,
    18, 18
  )

  private val LITERAL_SYMBOLS = LITERALS + 1 + LENGTH_BASE.size
  private val DISTANCE_SYMBOLS = DISTANCE_BASE.size

  private fun lengthIndexOf(length: Int): Int {
    var index = LENGTH_BASE.size - 1
    while (index > 0 && LENGTH_BASE[index] > length) index--
    return index
  }

  private fun distanceIndexOf(distance: Int): Int {
    var index = DISTANCE_BASE.size - 1
    while (index > 0 && DISTANCE_BASE[index] > distance) index--
    return index
  }

  private fun windowFor(size: Int): Int {
    var window = 1 shl 12
    while (window < size && window < MAX_WINDOW) window = window shl 1
    return window
  }

  private fun hash4(data: ByteArray, position: Int): Int {
    val value = (data[position].toInt() and 0xFF) or
      ((data[position + 1].toInt() and 0xFF) shl 8) or
      ((data[position + 2].toInt() and 0xFF) shl 16) or
      ((data[position + 3].toInt() and 0xFF) shl 24)
    return ((value * -1640531527) ushr (32 - HASH_BITS)) and (HASH_SIZE - 1)
  }

  private fun matchLength(data: ByteArray, candidate: Int, position: Int): Int {
    var length = 0
    val max = minOf(data.size - position, MAX_MATCH)
    while (length < max && data[candidate + length] == data[position + length]) {
      length++
    }
    return length
  }
}
