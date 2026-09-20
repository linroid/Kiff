package com.linroid.kiff.io

import okio.Path.Companion.toPath

/**
 * Whole-file reads and writes, for the patch itself and for restored output.
 *
 * The inputs to a diff are not read this way: they are opened as a [SeekableSource], because a
 * delta addresses its source rather than consuming it in order.
 */
object KiffFiles {

  fun readBytes(path: String): ByteArray =
    systemFileSystem.read(path.toPath()) { readByteArray() }

  fun writeBytes(path: String, bytes: ByteArray) {
    systemFileSystem.write(path.toPath()) { write(bytes) }
  }

  /**
   * Writes a file as the bytes arrive, so producing one never means holding it.
   *
   * The restore path is the reason this exists: a patch describes its target front to back, and
   * the machines that apply patches are the ones with the least room to spare.
   */
  fun writeStreaming(path: String, block: (RestoreTarget) -> Unit) {
    systemFileSystem.write(path.toPath()) {
      val sink = this
      block(object : RestoreTarget {
        override fun write(bytes: ByteArray, from: Int, to: Int) {
          sink.write(bytes, from, to - from)
        }
      })
    }
  }

  fun exists(path: String): Boolean = systemFileSystem.exists(path.toPath())

  fun size(path: String): Long? = systemFileSystem.metadataOrNull(path.toPath())?.size
}
