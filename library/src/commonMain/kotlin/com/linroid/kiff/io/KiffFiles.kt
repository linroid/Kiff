package com.linroid.kiff.io

import kotlin.random.Random
import okio.BufferedSink
import okio.Path.Companion.toPath

/**
 * Whole-file reads and writes, for the patch itself and for restored output.
 *
 * The inputs to a diff are not read this way: they are opened as a [SeekableSource], because a
 * delta addresses its source rather than consuming it in order.
 *
 * Every write here goes to a temporary file beside the destination, which replaces the destination
 * only once it is complete. A restore can only be verified once its bytes have been written, so a
 * restore written straight to its destination would leave a refused patch's output behind under the
 * name that was asked for - after first truncating whatever was there, which is the source itself
 * when a file is updated in place. This way the destination holds what it held before or the whole
 * result, never anything in between.
 */
object KiffFiles {

  fun readBytes(path: String): ByteArray =
    systemFileSystem.read(path.toPath()) { readByteArray() }

  fun writeBytes(path: String, bytes: ByteArray) {
    replace(path) { write(bytes) }
  }

  /**
   * Writes a file as the bytes arrive, so producing one never means holding it.
   *
   * The restore path is the reason this exists: a patch describes its target front to back, and
   * the machines that apply patches are the ones with the least room to spare. The file appears at
   * [path] only once [block] returns; if it throws, [path] is left as it was.
   */
  fun writeStreaming(path: String, block: (RestoreTarget) -> Unit) {
    replace(path) {
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

  /**
   * Writes [path] by way of a sibling temporary file, moved over [path] once [contents] returns.
   *
   * A sibling, because a move is only atomic within one file system.
   */
  private fun replace(path: String, contents: BufferedSink.() -> Unit) {
    val temporary = "$path.${Random.nextLong().toULong().toString(16)}.kiff-tmp".toPath()
    try {
      systemFileSystem.write(temporary, mustCreate = true, contents)
      systemFileSystem.atomicMove(temporary, path.toPath())
    } catch (e: Throwable) {
      // The failure being reported is the one that matters; a leftover temporary file is not.
      runCatching { systemFileSystem.delete(temporary, mustExist = false) }
      throw e
    }
  }
}
