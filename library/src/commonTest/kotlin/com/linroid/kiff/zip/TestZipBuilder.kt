package com.linroid.kiff.zip

import com.linroid.kiff.format.Crc32

/** Builds real zip archives in memory, including the odd corners Kiff has to reproduce exactly. */
class TestZipBuilder {

  private class Entry(
    val name: String,
    val data: ByteArray,
    val method: Int,
    val extra: ByteArray,
    val dataDescriptor: Boolean,
    val time: Int
  )

  private val entries = mutableListOf<Entry>()
  private var preamble = ByteArray(0)
  private var beforeDirectory = ByteArray(0)
  private var comment = ByteArray(0)

  fun entry(
    name: String,
    data: ByteArray,
    method: Int = 0,
    extra: ByteArray = ByteArray(0),
    dataDescriptor: Boolean = false,
    time: Int = 0x4321
  ): TestZipBuilder {
    entries.add(Entry(name, data, method, extra, dataDescriptor, time))
    return this
  }

  fun entry(name: String, text: String): TestZipBuilder = entry(name, text.encodeToByteArray())

  /** Bytes before the first local header, as a self-extracting archive would have. */
  fun preamble(bytes: ByteArray): TestZipBuilder = apply { preamble = bytes }

  /** Bytes between the last entry and the central directory, like an APK signing block. */
  fun beforeDirectory(bytes: ByteArray): TestZipBuilder = apply { beforeDirectory = bytes }

  fun comment(text: String): TestZipBuilder = apply { comment = text.encodeToByteArray() }

  fun build(): ByteArray {
    val out = LittleEndianWriter()
    out.bytes(preamble)
    val offsets = IntArray(entries.size)
    entries.forEachIndexed { index, entry ->
      offsets[index] = out.size
      val crc = Crc32.compute(entry.data)
      val flags = if (entry.dataDescriptor) 0x08 else 0x00
      out.u32(0x04034B50)
      out.u16(20)
      out.u16(flags)
      out.u16(entry.method)
      out.u16(entry.time)
      out.u16(0x5555)
      if (entry.dataDescriptor) {
        out.u32(0)
        out.u32(0)
        out.u32(0)
      } else {
        out.u32(crc.toLong())
        out.u32(entry.data.size.toLong())
        out.u32(entry.data.size.toLong())
      }
      out.u16(entry.name.encodeToByteArray().size)
      out.u16(entry.extra.size)
      out.bytes(entry.name.encodeToByteArray())
      out.bytes(entry.extra)
      out.bytes(entry.data)
      if (entry.dataDescriptor) {
        out.u32(0x08074B50)
        out.u32(crc.toLong())
        out.u32(entry.data.size.toLong())
        out.u32(entry.data.size.toLong())
      }
    }
    out.bytes(beforeDirectory)

    val directoryStart = out.size
    entries.forEachIndexed { index, entry ->
      val crc = Crc32.compute(entry.data)
      out.u32(0x02014B50)
      out.u16(20)
      out.u16(20)
      out.u16(if (entry.dataDescriptor) 0x08 else 0x00)
      out.u16(entry.method)
      out.u16(entry.time)
      out.u16(0x5555)
      out.u32(crc.toLong())
      out.u32(entry.data.size.toLong())
      out.u32(entry.data.size.toLong())
      out.u16(entry.name.encodeToByteArray().size)
      out.u16(entry.extra.size)
      out.u16(0)
      out.u16(0)
      out.u16(0)
      out.u32(0)
      out.u32(offsets[index].toLong())
      out.bytes(entry.name.encodeToByteArray())
      out.bytes(entry.extra)
    }
    val directorySize = out.size - directoryStart

    out.u32(0x06054B50)
    out.u16(0)
    out.u16(0)
    out.u16(entries.size)
    out.u16(entries.size)
    out.u32(directorySize.toLong())
    out.u32(directoryStart.toLong())
    out.u16(comment.size)
    out.bytes(comment)
    return out.toByteArray()
  }
}

private class LittleEndianWriter {
  private var buffer = ByteArray(1024)
  private var length = 0

  val size: Int get() = length

  fun u16(value: Int) {
    ensure(2)
    buffer[length++] = value.toByte()
    buffer[length++] = (value ushr 8).toByte()
  }

  fun u32(value: Long) {
    u16((value and 0xFFFF).toInt())
    u16(((value ushr 16) and 0xFFFF).toInt())
  }

  fun bytes(data: ByteArray) {
    ensure(data.size)
    data.copyInto(buffer, length)
    length += data.size
  }

  fun toByteArray(): ByteArray = buffer.copyOf(length)

  private fun ensure(extra: Int) {
    if (length + extra <= buffer.size) return
    var capacity = buffer.size
    while (capacity < length + extra) capacity *= 2
    buffer = buffer.copyOf(capacity)
  }
}
