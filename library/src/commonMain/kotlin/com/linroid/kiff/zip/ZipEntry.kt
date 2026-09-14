package com.linroid.kiff.zip

/** One entry of a zip archive, located inside the archive bytes. */
internal class ZipEntry(
  val name: String,
  val method: Int,
  val crc32: UInt,
  val compressedSize: Int,
  val uncompressedSize: Int,
  /** Start of the local file header. */
  val localHeaderOffset: Int,
  /** Start of the entry data, i.e. the end of the local file header. */
  val dataOffset: Int,
  /** End of the whole local record: data plus any data descriptor. */
  val recordEnd: Int
) {
  val headerSize: Int get() = dataOffset - localHeaderOffset

  /** Bytes after the data, i.e. a data descriptor when the entry has one. */
  val trailerSize: Int get() = recordEnd - dataOffset - compressedSize

  val isStored: Boolean get() = method == 0

  override fun toString() = "ZipEntry($name, method=$method, $compressedSize bytes)"
}
