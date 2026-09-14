package com.linroid.kiff.io

import com.linroid.kiff.KiffException
import java.io.IOException
import java.io.RandomAccessFile

actual fun fileSource(path: String): SeekableSource = try {
  RandomAccessFileSource(RandomAccessFile(path, "r"))
} catch (e: IOException) {
  throw KiffException.UnsupportedInput("Cannot open $path for reading: ${e.message}")
}

private class RandomAccessFileSource(private val file: RandomAccessFile) : SeekableSource {

  override val size: Long = file.length()

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    file.seek(position)
    return file.read(into, offset, length)
  }

  override fun close() = file.close()
}
