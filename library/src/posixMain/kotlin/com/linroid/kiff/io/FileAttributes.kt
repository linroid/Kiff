package com.linroid.kiff.io

import kotlinx.cinterop.ExperimentalForeignApi
import okio.Path
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

// copyPermissions lives in appleMain and linuxMain rather than here: mode_t is 16 bits on Apple
// and 32 on Linux, and the shared POSIX metadata compilation refuses code that touches a number
// whose width differs between the platforms it covers.

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncToDisk(path: Path) {
  val descriptor = open(path.toString(), O_RDONLY)
  if (descriptor < 0) return
  fsync(descriptor)
  close(descriptor)
}
