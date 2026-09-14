package com.linroid.kiff.text

import com.linroid.kiff.io.KiffFiles
import com.linroid.kiff.io.systemFileSystem
import okio.Path.Companion.toPath

/** Text-file reader behind the CLI's line diff. */
class TextFile(private val path: String) {

  fun readLines(): List<String> =
    systemFileSystem.read(path.toPath()) { readUtf8() }
      .split("\n")
      .dropLastWhile { it.isEmpty() }

  fun readBytes(): ByteArray = KiffFiles.readBytes(path)

  fun close() {}
}
