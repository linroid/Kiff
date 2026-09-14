package com.linroid.kiff.io

import com.linroid.kiff.KiffException

/** `eval` keeps the lookup opaque to bundlers, so a browser build does not try to resolve `fs`. */
private fun nodeFsOrNull(): dynamic =
  js("(typeof require === 'function') ? eval('require')('fs') : null")

actual fun fileSource(path: String): SeekableSource {
  val fs = nodeFsOrNull()
    ?: throw KiffException.UnsupportedInput(
      "This JS target has no file system; wrap the bytes with ByteArraySource instead"
    )
  val size = (fs.statSync(path).size as Number).toDouble().toLong()
  val descriptor = fs.openSync(path, "r") as Int
  return NodeFileSource(fs, descriptor, size)
}

private class NodeFileSource(
  private val fs: dynamic,
  private val descriptor: Int,
  override val size: Long
) : SeekableSource {

  override fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
    if (position >= size) return -1
    val count = minOf(length.toLong(), size - position).toInt()
    if (count <= 0) return 0
    val read = fs.readSync(descriptor, into, offset, count, position.toDouble()) as Int
    return if (read <= 0) -1 else read
  }

  override fun close() {
    fs.closeSync(descriptor)
  }
}
