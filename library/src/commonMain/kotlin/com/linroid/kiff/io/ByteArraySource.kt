package com.linroid.kiff.io

/** A [SeekableSource] over bytes already in memory. */
class ByteArraySource(internal val bytes: ByteArray) : SeekableSource {

  override val size: Long get() = bytes.size.toLong()

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= bytes.size) return -1
    val count = minOf(length.toLong(), bytes.size - position).toInt()
    if (count <= 0) return 0
    bytes.copyInto(into, offset, position.toInt(), position.toInt() + count)
    return count
  }
}

/** Wraps these bytes as a [SeekableSource] without copying them. */
fun ByteArray.asSource(): SeekableSource = ByteArraySource(this)
