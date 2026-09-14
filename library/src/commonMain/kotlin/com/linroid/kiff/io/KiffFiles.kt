package com.linroid.kiff.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * Whole-file reads and writes.
 *
 * The patchers need random access to both files, so they work on byte arrays rather than streams;
 * peak memory is roughly source + target + patch.
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
