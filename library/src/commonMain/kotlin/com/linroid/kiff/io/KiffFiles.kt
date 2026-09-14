package com.linroid.kiff.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Whole-file reads and writes, for the patch itself and for restored output.
 *
 * The inputs to a diff are not read this way: they are opened as a [SeekableSource], because a
 * delta addresses its source rather than consuming it in order.
 */
object KiffFiles {

  fun readBytes(path: String): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }

  fun writeBytes(path: String, bytes: ByteArray) {
    SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
  }

  fun exists(path: String): Boolean = SystemFileSystem.exists(Path(path))

  fun size(path: String): Long? = SystemFileSystem.metadataOrNull(Path(path))?.size
}
