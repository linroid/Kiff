package com.linroid.kiff.io

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString

class FileSource(private val path: String) {
  fun readLines(): List<String> {
    val text = SystemFileSystem.source(Path(path)).buffered().use {
      it.readString()
    }
    return text.split("\n").dropLastWhile { it.isEmpty() }
  }

  fun readBytes(): ByteArray =
    SystemFileSystem.source(Path(path)).buffered().use {
      it.readByteArray()
    }

  fun close() {}
}
