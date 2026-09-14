package com.linroid.kiff.internal

import com.linroid.kiff.KiffException

/**
 * Small LZ77/LZSS codec used to pack the literal stream of a delta.
 *
 * Deliberately simple and allocation-light: a 64 KiB window, a fixed hash-chain match finder, and a
 * varint token stream. It exists so patches stay small without pulling a platform compressor into
 * common code, not to compete with zlib.
 */
internal object Lzss {

  private const val MIN_MATCH = 4
  private const val WINDOW = 1 shl 16
  private const val WINDOW_MASK = WINDOW - 1
  private const val HASH_BITS = 17
  private const val HASH_SIZE = 1 shl HASH_BITS
  private const val MAX_CHAIN = 24

  fun compress(data: ByteArray): ByteArray {
    val out = ByteWriter(data.size / 3 + 32)
    val head = IntArray(HASH_SIZE) { -1 }
    val prev = IntArray(WINDOW)

    var position = 0
    var literalStart = 0

    while (position < data.size) {
      if (position + MIN_MATCH > data.size) {
        position = data.size
        break
      }
      val hash = hash4(data, position)
      var candidate = head[hash]
      var bestLength = 0
      var bestDistance = 0
      var chain = 0
      while (candidate >= 0 && chain < MAX_CHAIN) {
        val distance = position - candidate
        if (distance <= 0 || distance > WINDOW) break
        val length = matchLength(data, candidate, position)
        if (length > bestLength) {
          bestLength = length
          bestDistance = distance
          if (length >= 255) break
        }
        candidate = prev[candidate and WINDOW_MASK]
        chain++
      }
      prev[position and WINDOW_MASK] = head[hash]
      head[hash] = position

      if (bestLength >= MIN_MATCH) {
        flushLiterals(out, data, literalStart, position)
        out.writeVarInt(((bestLength - MIN_MATCH) shl 1) or 1)
        out.writeVarInt(bestDistance - 1)
        // Register the interior of the match so later positions can still find matches.
        for (i in position + 1 until position + bestLength) {
          if (i + MIN_MATCH > data.size) break
          val h = hash4(data, i)
          prev[i and WINDOW_MASK] = head[h]
          head[h] = i
        }
        position += bestLength
        literalStart = position
      } else {
        position++
      }
    }
    flushLiterals(out, data, literalStart, data.size)
    out.writeVarInt(0)
    return out.toByteArray()
  }

  fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
    val result = ByteArray(expectedSize)
    val reader = ByteReader(data)
    var position = 0
    while (true) {
      val tag = reader.readVarInt()
      if (tag == 0) break
      if (tag and 1 == 0) {
        val count = tag ushr 1
        checkFits(position, count, expectedSize)
        reader.readBytes(count).copyInto(result, position)
        position += count
      } else {
        val length = (tag ushr 1) + MIN_MATCH
        val distance = reader.readVarInt() + 1
        if (distance > position) {
          throw KiffException.InvalidPatch("Literal back-reference $distance before start")
        }
        checkFits(position, length, expectedSize)
        var from = position - distance
        repeat(length) {
          result[position++] = result[from++]
        }
      }
    }
    if (position != expectedSize) {
      throw KiffException.InvalidPatch("Literal stream produced $position of $expectedSize bytes")
    }
    return result
  }

  private fun checkFits(position: Int, count: Int, expectedSize: Int) {
    if (count < 0 || position + count > expectedSize) {
      throw KiffException.InvalidPatch("Literal stream overflows $expectedSize bytes")
    }
  }

  private fun flushLiterals(out: ByteWriter, data: ByteArray, from: Int, to: Int) {
    var start = from
    while (start < to) {
      // Cap runs so the length token stays small and the decoder can stream them.
      val end = minOf(to, start + MAX_LITERAL_RUN)
      out.writeVarInt((end - start) shl 1)
      out.writeBytes(data, start, end)
      start = end
    }
  }

  private const val MAX_LITERAL_RUN = 1 shl 16

  private fun hash4(data: ByteArray, position: Int): Int {
    val value = (data[position].toInt() and 0xFF) or
      ((data[position + 1].toInt() and 0xFF) shl 8) or
      ((data[position + 2].toInt() and 0xFF) shl 16) or
      ((data[position + 3].toInt() and 0xFF) shl 24)
    return ((value * -1640531527) ushr (32 - HASH_BITS)) and (HASH_SIZE - 1)
  }

  private fun matchLength(data: ByteArray, candidate: Int, position: Int): Int {
    var length = 0
    val max = data.size - position
    while (length < max && data[candidate + length] == data[position + length]) {
      length++
    }
    return length
  }
}
