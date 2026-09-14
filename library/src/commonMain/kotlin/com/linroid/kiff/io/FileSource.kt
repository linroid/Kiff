package com.linroid.kiff.io

import okio.Path.Companion.toPath

/** Text-file reader behind the example CLI's line diff. */
class FileSource(private val path: String) {

  fun readLines(): List<String> =
    systemFileSystem.read(path.toPath()) { readUtf8() }
      .split("\n")
      .dropLastWhile { it.isEmpty() }

  fun readBytes(): ByteArray = KiffFiles.readBytes(path)

  fun close() {}
}
