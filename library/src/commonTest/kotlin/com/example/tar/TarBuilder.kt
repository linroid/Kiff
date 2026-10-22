package com.example.tar

import com.linroid.kiff.io.SeekableSource

/** Builds ustar archives in memory, with the corners [TarFormat] has to tile exactly. */
class TarBuilder {

  private val members = mutableListOf<ByteArray>()

  fun file(name: String, data: ByteArray) = apply {
    members += header(name, data.size.toLong()) + data + ByteArray(padding(data.size.toLong()))
  }

  fun file(name: String, text: String) = file(name, text.encodeToByteArray())

  fun directory(name: String) = apply { members += header(name, 0, type = '5') }

  /**
   * The archive, ended by [endBlocks] zero blocks, padded with zeros to a whole number of
   * [recordSize]-byte records as tar does by default, and followed by [trailer].
   */
  fun build(
    endBlocks: Int = 2,
    recordSize: Int = 10_240,
    trailer: ByteArray = ByteArray(0)
  ): ByteArray {
    var archive = members.fold(ByteArray(0)) { out, member -> out + member } +
      ByteArray(endBlocks * TarFormat.BLOCK)
    if (recordSize > 0 && archive.size % recordSize != 0) {
      archive += ByteArray(recordSize - archive.size % recordSize)
    }
    return archive + trailer
  }

  companion object {

    /**
     * A ustar header for a member of [size] bytes. A size octal cannot hold, or any size when
     * [base256] is set, is written in GNU's base-256 form.
     */
    fun header(name: String, size: Long, type: Char = '0', base256: Boolean = false): ByteArray {
      val header = ByteArray(TarFormat.BLOCK)
      val (prefix, rest) = if (name.length > 100) {
        val cut = name.lastIndexOf('/', 155)
        name.substring(0, cut) to name.substring(cut + 1)
      } else {
        "" to name
      }
      rest.encodeToByteArray().copyInto(header, 0)
      octal(header, 100, 8, 0b110_100_100) // mode
      octal(header, 108, 8, 0) // uid
      octal(header, 116, 8, 0) // gid
      if (base256 || size > MAX_OCTAL_SIZE) {
        header[124] = 0x80.toByte()
        for (i in 0 until 8) header[135 - i] = (size ushr (8 * i)).toByte()
      } else {
        octal(header, 124, 12, size)
      }
      octal(header, 136, 12, 0) // mtime
      header[156] = type.code.toByte()
      "ustar".encodeToByteArray().copyInto(header, 257)
      "00".encodeToByteArray().copyInto(header, 263)
      prefix.encodeToByteArray().copyInto(header, 345)

      for (i in 148 until 156) header[i] = 0x20
      val sum = header.sumOf { it.toInt() and 0xFF }
      octal(header, 148, 7, sum.toLong())
      return header
    }

    /** Zeros that bring [size] bytes of data up to a whole block. */
    fun padding(size: Long): Int = ((TarFormat.BLOCK - size % TarFormat.BLOCK) % TarFormat.BLOCK)
      .toInt()

    /** [length] - 1 octal digits, zero-filled, then a NUL. */
    private fun octal(header: ByteArray, at: Int, length: Int, value: Long) {
      val digits = value.toString(8).padStart(length - 1, '0')
      check(digits.length == length - 1) { "$value does not fit in $length bytes" }
      digits.encodeToByteArray().copyInto(header, at)
      header[at + length - 1] = 0
    }

    private const val MAX_OCTAL_SIZE = 8L * 1024 * 1024 * 1024 - 1
  }
}

/**
 * A source of [size] bytes that are all zero but for [blocks], placed by position: an archive of
 * any size, built for the cost of its headers. It counts what is read from it.
 */
class SparseSource(override val size: Long, private val blocks: Map<Long, ByteArray>) :
  SeekableSource {

  var bytesRead: Long = 0
    private set

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    val count = minOf(length.toLong(), size - position).toInt()
    into.fill(0, offset, offset + count)
    for ((at, bytes) in blocks) {
      val from = maxOf(at, position)
      val to = minOf(at + bytes.size, position + count)
      if (from < to) {
        val start = offset + (from - position).toInt()
        bytes.copyInto(into, start, (from - at).toInt(), (to - at).toInt())
      }
    }
    bytesRead += count
    return count
  }
}
