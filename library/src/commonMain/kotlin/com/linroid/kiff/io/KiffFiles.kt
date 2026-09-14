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

  fun exists(path: String): Boolean = systemFileSystem.exists(path.toPath())

  fun size(path: String): Long? = systemFileSystem.metadataOrNull(path.toPath())?.size
}
