package com.linroid.kiff.io

import kotlin.random.Random
import okio.BufferedSink
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

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
 *
 * A replaced file keeps its permission bits, and a symlink is followed so the file it names is the
 * one replaced; a hard link, like any rename, is split. Replacing needs a writable directory and
 * room for both files. A run killed partway can leave a `.kiff-*.tmp` file beside the destination,
 * which is safe to delete.
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

  /** Whether [first] and [second] name one existing file, through any symlinks. */
  internal fun sameFile(first: String, second: String): Boolean {
    val a = first.toPath()
    val b = second.toPath()
    if (!systemFileSystem.exists(a) || !systemFileSystem.exists(b)) return false
    return systemFileSystem.canonicalize(a) == systemFileSystem.canonicalize(b)
  }

  fun size(path: String): Long? = systemFileSystem.metadataOrNull(path.toPath())?.size

  /**
   * Writes [path] by way of a sibling temporary file, moved over [path] once [contents] returns.
   *
   * A sibling, because a move is only atomic within one file system.
   */
  private fun replace(path: String, contents: BufferedSink.() -> Unit) {
    val requested = path.toPath()
    // Through a symlink to the file it names, so the link survives an update in place. A link to
    // nothing is replaced like any other missing file.
    val destination = when {
      systemFileSystem.exists(requested) -> systemFileSystem.canonicalize(requested)
      else -> requested
    }
    val existing = systemFileSystem.metadataOrNull(destination)
    // Both found out before anything is written, rather than after a whole restore.
    if (existing?.isDirectory == true) throw IOException("Cannot write $path: it is a directory")
    val directory = destination.parent ?: ".".toPath()
    if (systemFileSystem.metadataOrNull(directory)?.isDirectory != true) {
      throw IOException("Cannot write $path: $directory is not a directory")
    }
    // A short name of its own rather than the destination's with more on the end, so a name near
    // the file system's limit still has room beside it.
    val temporary = directory / ".kiff-${Random.nextLong().toULong().toString(16)}.tmp"
    try {
      val handle = try {
        systemFileSystem.openReadWrite(temporary, mustCreate = true, mustExist = false)
      } catch (e: IOException) {
        throw IOException("Cannot write $path: ${e.message}", e)
      }
      handle.use {
        // Before any byte arrives, so a private file's contents are never readable by others in
        // the meantime, and an executable updated in place is still one afterwards.
        if (existing != null) copyPermissions(destination, temporary)
        handle.sink().buffer().use { it.contents() }
      }
      syncToDisk(temporary)
      try {
        systemFileSystem.atomicMove(temporary, destination)
      } catch (e: IOException) {
        throw IOException("Cannot write $path: ${e.message}", e)
      }
      // The move is only durable once the directory that records it is.
      syncToDisk(directory)
    } catch (e: Throwable) {
      // The failure being reported is the one that matters; a leftover temporary file is not.
      runCatching { systemFileSystem.delete(temporary, mustExist = false) }
      throw e
    }
  }
}
