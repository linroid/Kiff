package com.linroid.kiff.io

import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import okio.Path

internal actual fun copyPermissions(from: Path, to: Path) {
  // A file system without POSIX permissions has none to carry over.
  if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
  try {
    val permissions = Files.getPosixFilePermissions(Paths.get(from.toString()))
    Files.setPosixFilePermissions(Paths.get(to.toString()), permissions)
  } catch (e: Exception) {
    // Best effort: the file keeps the permissions it was created with.
  }
}

internal actual fun syncToDisk(path: Path) {
  try {
    FileChannel.open(Paths.get(path.toString()), StandardOpenOption.READ).use { it.force(true) }
  } catch (e: Exception) {
    // Some platforms cannot sync through a read-only channel, or a directory at all.
  }
}
