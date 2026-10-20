package com.linroid.kiff.io

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import okio.Path
import platform.posix.O_RDONLY
import platform.posix.chmod
import platform.posix.close
import platform.posix.fsync
import platform.posix.open
import platform.posix.stat

// mode_t is 16 bits on Apple and 32 on Linux. Only its low twelve bits are used and it never
// leaves this function, so the width doesn't matter.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun copyPermissions(from: Path, to: Path) {
  memScoped {
    val info = alloc<stat>()
    if (stat(from.toString(), info.ptr) != 0) return
    // The permission, setuid, setgid and sticky bits; the rest of the mode is the file's type.
    chmod(to.toString(), (info.st_mode.toInt() and 0xFFF).convert())
  }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncToDisk(path: Path) {
  val descriptor = open(path.toString(), O_RDONLY)
  if (descriptor < 0) return
  fsync(descriptor)
  close(descriptor)
}
